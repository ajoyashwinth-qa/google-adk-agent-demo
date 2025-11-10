import com.google.genai.errors.ServerException;

import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small retry helper for GenAI calls. Use this to wrap calls that may surface
 * com.google.genai.errors.ServerException (e.g. 503 model overloaded).
 *
 * Usage example:
 *   String result = RetryUtils.executeWithRetries(() -> myClient.generate(...), 5, 1000, 30000, 500);
 */
public class RetryUtils {
    private static final Logger logger = Logger.getLogger(RetryUtils.class.getName());
    private static final Random RNG = new Random();

    public static <T> T executeWithRetries(Callable<T> callable,
                                           int maxAttempts,
                                           long baseDelayMs,
                                           long maxDelayMs,
                                           long maxJitterMs) throws Exception {
        Exception lastEx = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                logger.info("RetryUtils: attempt " + attempt + " of " + maxAttempts);
                return callable.call();
            } catch (ExecutionException ee) {
                // Unwrap ExecutionException
                Throwable cause = ee.getCause();
                if (cause instanceof ServerException) {
                    lastEx = (ServerException) cause;
                    logger.log(Level.WARNING, "ServerException (wrapped) on attempt " + attempt + ": " + cause.getMessage(), cause);
                    Long suggestedDelay = inspectForRetryAfter(cause);
                    if (attempt == maxAttempts) break;
                    long wait = chooseDelay(attempt, baseDelayMs, maxDelayMs, maxJitterMs, suggestedDelay);
                    logger.info("RetryUtils: sleeping " + wait + " ms before retrying");
                    Thread.sleep(wait);
                    continue;
                } else {
                    // non-server exception, rethrow
                    throw ee;
                }
            } catch (ServerException se) {
                lastEx = se;
                logger.log(Level.WARNING, "ServerException on attempt " + attempt + ": " + se.getMessage(), se);
                Long suggestedDelay = inspectForRetryAfter(se);
                if (attempt == maxAttempts) break;
                long wait = chooseDelay(attempt, baseDelayMs, maxDelayMs, maxJitterMs, suggestedDelay);
                logger.info("RetryUtils: sleeping " + wait + " ms before retrying");
                Thread.sleep(wait);

            } catch (Exception e) {
                // Unexpected exception; don't retry unless it's wrapping ServerException
                Throwable cause = (e instanceof ExecutionException) ? e.getCause() : e;
                if (cause instanceof ServerException) {
                    lastEx = (ServerException) cause;
                    logger.log(Level.WARNING, "ServerException (wrapped) on attempt " + attempt + ": " + cause.getMessage(), cause);
                    Long suggestedDelay = inspectForRetryAfter(cause);
                    if (attempt == maxAttempts) break;
                    long wait = chooseDelay(attempt, baseDelayMs, maxDelayMs, maxJitterMs, suggestedDelay);
                    logger.info("RetryUtils: sleeping " + wait + " ms before retrying");
                    Thread.sleep(wait);
                } else {
                    // non-server exception; propagate immediately
                    throw e;
                }
            }
        }

        // All retries exhausted
        if (lastEx != null) throw lastEx;
        throw new Exception("RetryUtils: all attempts failed");
    }

    private static long chooseDelay(int attempt, long baseDelayMs, long maxDelayMs, long maxJitterMs, Long suggestedDelayMs) {
        if (suggestedDelayMs != null && suggestedDelayMs > 0) {
            return Math.min(suggestedDelayMs, maxDelayMs);
        }
        long delay = baseDelayMs * (1L << (attempt - 1));
        if (delay > maxDelayMs) delay = maxDelayMs;
        long jitter = (long) (RNG.nextDouble() * maxJitterMs);
        return delay + jitter;
    }

    // inspect exception reflectively for possible Retry-After info
    private static Long inspectForRetryAfter(Throwable t) {
        if (t == null) return null;
        try {
            String[] candidateMethods = new String[]{"getStatusCode", "getCode", "getStatus", "getHttpStatus", "statusCode", "getResponse", "getHeaders", "getResponseHeaders", "getRetryAfter", "retryAfter"};
            for (String mName : candidateMethods) {
                try {
                    Method m = t.getClass().getMethod(mName);
                    if (m.getParameterCount() == 0) {
                        Object result = m.invoke(t);
                        if (result != null) {
                            if (result instanceof Map) {
                                Map<?, ?> map = (Map<?, ?>) result;
                                for (Object key : map.keySet()) {
                                    String k = String.valueOf(key);
                                    if (k.equalsIgnoreCase("retry-after") || k.equalsIgnoreCase("Retry-After")) {
                                        Long parsed = parseRetryAfterValue(map.get(key));
                                        if (parsed != null) return parsed;
                                    }
                                }
                            } else {
                                Long parsed = parseRetryAfterValue(result);
                                if (parsed != null) return parsed;
                            }
                        }
                    }
                } catch (NoSuchMethodException nsme) {
                    // ignore
                }
            }

            Throwable cause = t.getCause();
            if (cause != null) return inspectForRetryAfter(cause);
        } catch (Exception ex) {
            logger.log(Level.FINE, "Error inspecting for Retry-After: " + ex.getMessage(), ex);
        }
        return null;
    }

    private static Long parseRetryAfterValue(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number) {
                long seconds = ((Number) v).longValue();
                return seconds <= 0 ? null : seconds * 1000L;
            }
            String s = String.valueOf(v).trim();
            if (s.matches("^\\d+$")) {
                long seconds = Long.parseLong(s);
                return seconds * 1000L;
            }
            if (looksLikeHttpDate(s)) {
                try {
                    ZonedDateTime date = ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME);
                    long ms = date.toInstant().toEpochMilli() - System.currentTimeMillis();
                    return ms > 0 ? ms : null;
                } catch (Exception ex) {
                    // ignore
                }
            }
        } catch (Exception ex) {
            // ignore
        }
        return null;
    }

    private static boolean looksLikeHttpDate(String s) {
        if (s == null) return false;
        return s.contains(",") && s.matches(".*\\d{4}.*");
    }
}
