/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.job.alert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;

import com.addthis.basis.util.LessStrings;
import com.addthis.basis.util.Parameter;

import com.addthis.bundle.core.Bundle;
import com.addthis.bundle.core.BundleFormat;
import com.addthis.bundle.core.list.ListBundle;
import com.addthis.bundle.value.ValueFactory;
import com.addthis.codec.config.Configs;
import com.addthis.hydra.data.filter.bundle.BundleFilter;
import com.addthis.hydra.data.util.DateUtil;
import com.addthis.hydra.data.util.JSONFetcher;
import com.addthis.hydra.job.alert.types.BundleCanaryJobAlert;
import com.addthis.maljson.JSONArray;
import com.addthis.meshy.MeshyClient;
import com.addthis.meshy.service.file.FileReference;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JobAlertUtil {
    private static final Logger log = LoggerFactory.getLogger(JobAlertUtil.class);
    private static final int queryPort = Parameter.intValue("com.addthis.hydra.query.web.QueryServer.webPort", 2222);
    private static final String queryURLBase = "http://" +
                                               Parameter.value("alert.query.host", Parameter.value("spawn.queryhost"))
                                               + ":" + queryPort + "/query/call";
    private static final String defaultOps = "gather=s";
    private static final int alertQueryTimeout = Parameter.intValue("alert.query.timeout", 20_000);
    private static final int alertQueryRetries = Parameter.intValue("alert.query.retries", 4);
    private static final int alertQueryMinBackoff = Parameter.intValue("alert.query.backoff.min", 10_000);
    private static final int alertQueryMaxBackoff = Parameter.intValue("alert.query.backoff.max", 120_000);

    private static final Pattern QUERY_TRIM_PATTERN = Pattern.compile("[\\[\\]]");

    /**
     * Convert a jobId and path into a mesh directory path.
     */
    public static String meshLookupString(@Nonnull String jobId, @Nonnull String dirPath) {
        return("/job*/" + jobId + "/*/gold/" + DateUtil.expandDateMacro(dirPath));
    }

    /**
     * Count the total byte sizes of files along a certain path via mesh
     * @param jobId The job to check
     * @param dirPath The path to check within the jobId, e.g. split/{{now-1}}/importantfiles/*.gz
     * @return A map of hostUUID to the total byte size on that host
     */
    public static Map<String, Long> getTotalBytesFromMesh(@Nullable MeshyClient meshyClient,
                                                          @Nonnull String jobId, @Nonnull String dirPath) {
        String meshLookupString = meshLookupString(jobId, dirPath);
        if (meshyClient != null) {
            try {
                Map<String,Long> bytesPerHost = new HashMap<>();
                Collection<FileReference> fileRefs = meshyClient.listFiles(new String[]{meshLookupString});
                for (FileReference fileRef : fileRefs) {
                    String hostUUID = fileRef.getHostUUID();
                    Long bytes = bytesPerHost.get(hostUUID);
                    if (bytes == null) {
                        bytes = 0l;
                    }
                    bytes += fileRef.size;
                    bytesPerHost.put(hostUUID, bytes);
                }
                return bytesPerHost;
            } catch (IOException e) {
                log.warn("Job alert mesh look up failed", e);
            }
        } else {
            log.warn("Received mesh lookup request job={} dirPath={} while meshy client was not instantiated; returning zero", jobId, dirPath);
        }
        return ImmutableMap.of();
    }

    public static Map<String, Integer> getFileCountPerTask(@Nullable MeshyClient meshyClient,
                                                           @Nonnull String jobId, @Nonnull String dirPath) {
        String meshLookupString = meshLookupString(jobId, dirPath);
        Map<String, Integer> result = new HashMap<>();
        if (meshyClient != null) {
            try {
                Collection<FileReference> fileRefs = meshyClient.listFiles(new String[]{meshLookupString});
                for (FileReference fileRef : fileRefs) {
                    String uuid = fileRef.getHostUUID();
                    String path = fileRef.name;
                    int offset = path.indexOf("/gold/");
                    String key = uuid + ":" + path.substring(0, offset);
                    Integer count = result.get(key);
                    if (count == null) {
                        count = 1;
                    } else {
                        count = count + 1;
                    }
                    result.put(key, count);
                }
            } catch (IOException e) {
                log.warn("Job alert mesh look up failed", e);
            }
        } else {
            log.warn("Received mesh lookup request job={} dirPath={} while meshy client was not instantiated; returning zero", jobId, meshLookupString);
        }
        return result;
    }

    /**
     * Count the total number of hits along a certain path in a tree object
     * @param jobId The job to query
     * @param checkPath The path to check, e.g.
     * @return The number of hits along the specified path
     */
    public static long getQueryCount(String jobId, String checkPath) {
        String queryURL = getQueryURL(jobId, checkPath, defaultOps, defaultOps);

        HashSet<String> result = new JSONFetcher.SetLoader(queryURL)
                .setContention(alertQueryTimeout, alertQueryRetries, alertQueryMinBackoff, alertQueryMaxBackoff).load();
        if (result == null || result.isEmpty()) {
            log.warn("Found no data for job={} checkPath={}; returning zero", jobId, checkPath);
            return 0;
        } else if (result.size() > 1) {
            log.warn("Found multiple results for job={} checkPath={}; using first row", jobId, checkPath);
        }
        String raw = result.iterator().next();
        return Long.parseLong(QUERY_TRIM_PATTERN.matcher(raw).replaceAll("")); // Trim [] characters and parse as long

    }

    private static String testQueryResult(JSONArray array, BundleFilter filter) {
        StringBuilder errorBuilder = new StringBuilder();
        JSONArray headerRow = array.optJSONArray(0);
        String[] header = new String[headerRow.length()];
        for(int i = 0; i < header.length; i++) {
            header[i] = headerRow.optString(i);
        }
        for(int i = 1; i < array.length(); i++) {
            JSONArray row = array.optJSONArray(i);
            Bundle bundle = new ListBundle();
            BundleFormat format = bundle.getFormat();
            for(int j = 0; j < row.length(); j++) {
                bundle.setValue(format.getField(header[j]), ValueFactory.create(row.optString(j)));
            }
            try {
                if (!filter.filter(bundle)) {
                    errorBuilder.append("filter failed for row: ")
                                .append(i -1)
                                .append(" bundle: ")
                                .append(bundle)
                                .append('\n');
                    log.trace("Row {} filter result is FAILURE", i - 1);
                } else {
                    log.trace("Row {} filter result is SUCCESS", i - 1);
                }
            } catch(Exception ex) {
                log.warn("Error while evaluating row {}", i - 1, ex);
                errorBuilder.append(ex.toString());
            }
        }
        if (errorBuilder.length() > 0) {
            return errorBuilder.toString();
        } else {
            return null;
        }
    }

    public static String evaluateQueryWithFilter(BundleCanaryJobAlert alert, String jobId) {
        String query = alert.canaryPath;
        String ops = MoreObjects.firstNonNull(alert.canaryOps, "");
        String rops = MoreObjects.firstNonNull(alert.canaryRops, "");
        String filter = alert.canaryFilter;
        // prevent query results from overwhelming spawn
        ops += ";limit=1000;merge=kkkkkkkkkkkk";
        String url = getQueryURL(jobId, query, ops, rops);
        log.trace("Emitting query with url {}", url);
        JSONArray array = new JSONFetcher(alertQueryTimeout,
                                          alertQueryRetries,
                                          alertQueryMinBackoff,
                                          alertQueryMaxBackoff).loadJSONArray(url);
        StringBuilder errorBuilder = new StringBuilder();
        if (array.length() == 0) {
            errorBuilder.append("Header row is missing.\n");
        } else if (array.length() == 1) {
            errorBuilder.append("No data is present (only header row).\n");
        } else {
            errorBuilder.append(array.toString() + "\n");
        }
        /**
         * Test the following conditions:
         * - the array contains two or more values
         * - each value of the array is itself an array
         * - the lengths of all subarrays are identical
         */
        boolean valid = array.length() > 1;
        log.trace("Array contains two or more values: {}", array.length() > 1);
        JSONArray header = valid ? array.optJSONArray(0) : null;
        valid = valid && (header != null);
        log.trace("Header is an array: {}", header != null);
        for(int i = 1; valid && i < array.length(); i++) {
            JSONArray element = array.optJSONArray(i);
            log.trace("Element {} is an array: {}", i, element != null);
            if (element != null) {
                valid = (element.length() == header.length());
                log.trace("Element {} has correct length: {}", i, element.length() == header.length());
            } else {
                valid = false;
            }
        }
        BundleFilter bFilter = null;
        try {
            bFilter = Configs.decodeObject(BundleFilter.class, filter);
        } catch (Exception ex) {
            errorBuilder.append("Error attempting to create bundle filter: " + ex + "\n");
            log.error("Error attempting to create bundle filter", ex);
            valid = false;
        }
        if (valid) {
            return testQueryResult(array, bFilter);
        } else {
            return errorBuilder.toString();
        }
    }

    private static String getQueryURL(String jobId, String path, String ops, String rops) {
        return queryURLBase + "?job=" + jobId + "&path=" + LessStrings.urlEncode(DateUtil.expandDateMacro(path))
               + "&ops=" + LessStrings.urlEncode(ops) + "&rops=" + LessStrings.urlEncode(rops);
    }

}
