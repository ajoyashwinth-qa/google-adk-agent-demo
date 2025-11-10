import com.google.adk.agents.LlmAgent;
import com.google.adk.web.AdkWebServer;
import com.google.genai.errors.ServerException;

import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

public class agent_action {

    private static final Logger logger = Logger.getLogger(agent_action.class.getName());

    public static void main(String[] args) {
        System.out.println("Hello, Agent Action!");

        // Models to try in order. First is the preferred model; following entries are fallbacks.
        List<String> candidateModels = Arrays.asList(
                "gemini-2.5-flash",
                "gemini-2.1",
                "chat-bison"
        );

        final int attemptsPerModel = 3; // attempts for each model before falling back
        final long baseDelayMs = 1000; // 1 second base
        final long maxDelayMs = 30_000; // cap backoff at 30 seconds
        final long maxJitterMs = 500; // random jitter to avoid thundering herd

        boolean started = false;
        Exception lastEx = null;
        Random rng = new Random();

        // Try each candidate model in order; for each model, attempt up to attemptsPerModel times
        for (String model : candidateModels) {
            for (int attempt = 1; attempt <= attemptsPerModel && !started; attempt++) {
                Long suggestedDelayMs = null; // may be set by inspectServerException
                try {
                    logger.info("Starting server with model: " + model + " (attempt " + attempt + " of " + attemptsPerModel + ")");

                    LlmAgent agent = LlmAgent.builder()
                            .name("Agent Action")
                            .instruction("You are an expert weather agent. You have to predict the weather for next 3 days and explain it in simple and engaging manner. Always ask a follow-up question")
                            .model(model)
                            .build();

                    AdkWebServer.start(agent);

                    started = true;
                    logger.info("ADK web server started successfully with model: " + model);

                } catch (ServerException e) {
                    // These are the errors reported by the ADK / genai when the model is unavailable or overloaded.
                    lastEx = e;
                    logger.log(Level.WARNING, "Model/service error with model " + model + " on attempt " + attempt + ": " + e.getMessage(), e);
                    suggestedDelayMs = inspectServerException(e);

                } catch (Exception e) {
                    // Some library code wraps ServerException in ExecutionException (CompletableFuture); unwrap and treat as ServerException when applicable
                    Throwable cause = (e instanceof java.util.concurrent.ExecutionException) ? e.getCause() : e;
                    if (cause instanceof ServerException) {
                        ServerException se = (ServerException) cause;
                        lastEx = se;
                        logger.log(Level.WARNING, "Model/service error (wrapped) with model " + model + " on attempt " + attempt + ": " + se.getMessage(), se);
                        suggestedDelayMs = inspectServerException(se);
                    } else {
                        // Catch-all for unexpected issues (network, config, etc.)
                        lastEx = e;
                        logger.log(Level.SEVERE, "Unexpected error while starting ADK server with model " + model + ": " + e.getMessage(), e);
                        suggestedDelayMs = inspectServerException(e);
                    }
                }

                if (!started && attempt < attemptsPerModel) {
                    // Choose delay: prefer server-suggested Retry-After if present, otherwise exponential backoff with jitter and cap
                    long totalDelay;
                    if (suggestedDelayMs != null && suggestedDelayMs > 0) {
                        totalDelay = Math.min(suggestedDelayMs, maxDelayMs);
                        logger.info("Server suggested delay detected: " + suggestedDelayMs + " ms; using " + totalDelay + " ms before next try.");
                    } else {
                        long delay = baseDelayMs * (1L << (attempt - 1));
                        if (delay > maxDelayMs) {
                            delay = maxDelayMs;
                        }
                        long jitter = (long) (rng.nextDouble() * maxJitterMs);
                        totalDelay = delay + jitter;
                        logger.info("Retrying model " + model + " in " + totalDelay + " ms (attempt " + (attempt + 1) + " of " + attemptsPerModel + ")...");
                    }

                    try {
                        Thread.sleep(totalDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.log(Level.WARNING, "Retry sleep interrupted", ie);
                        break;
                    }
                }
            }

            if (started) {
                break; // server started successfully, exit outer loop
            } else {
                logger.info("Falling back to next model (if any)");
            }
        }

        if (!started) {
            System.err.println("ERROR: Failed to start ADK server after trying models: " + candidateModels + ".");
            if (lastEx != null) {
                lastEx.printStackTrace(System.err);
            }
            System.exit(1);
        }

        // Add a shutdown hook to make it clear when the program exits gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(() -> logger.info("Shutting down Agent Action server")));
    }

    // Diagnostic helper: reflectively inspect exception for status / headers / retry-after if available
    // Returns suggested delay in milliseconds (if found), otherwise null
    private static Long inspectServerException(Throwable t) {
        if (t == null) return null;
        try {
            logger.info("Inspecting exception: " + t.getClass().getName() + " -> " + t.getMessage());

            // Attempt to call common methods that may expose HTTP info
            String[] candidateMethods = new String[]{"getStatusCode", "getCode", "getStatus", "getHttpStatus", "statusCode", "getResponse", "getHeaders", "getResponseHeaders", "getRetryAfter", "retryAfter"};

            for (String mName : candidateMethods) {
                try {
                    Method m = t.getClass().getMethod(mName);
                    if (m.getParameterCount() == 0) {
                        Object result = m.invoke(t);
                        logger.info("Method " + mName + "() => " + String.valueOf(result));

                        if (result != null) {
                            // If it's a Map, check keys for Retry-After
                            if (result instanceof Map) {
                                Map<?, ?> map = (Map<?, ?>) result;
                                for (Object key : map.keySet()) {
                                    try {
                                        String keyStr = String.valueOf(key);
                                        if (keyStr.equalsIgnoreCase("retry-after") || keyStr.equalsIgnoreCase("Retry-After")) {
                                            Object v = map.get(key);
                                            Long ms = parseRetryAfterValue(v);
                                            if (ms != null) return ms;
                                        }
                                    } catch (Exception ex) {
                                        // ignore
                                    }
                                }
                            } else {
                                // If the method directly returned a Retry-After value
                                Long ms = parseRetryAfterValue(result);
                                if (ms != null) return ms;

                                // Check string for date-like content
                                String s = String.valueOf(result);
                                if (s.toLowerCase().contains("retry-after") || looksLikeHttpDate(s)) {
                                    logger.info("Possible retry info in returned object: " + s);
                                }
                            }
                        }
                    }
                } catch (NoSuchMethodException nsme) {
                    // method not present, skip
                } catch (Exception invokeEx) {
                    logger.info("Failed to invoke " + mName + "(): " + invokeEx.getMessage());
                }
            }

            // Also print cause chain
            Throwable cause = t.getCause();
            if (cause != null) {
                logger.info("Exception cause: " + cause.getClass().getName() + " -> " + cause.getMessage());
                // Recurse into cause for Retry-After
                Long fromCause = inspectServerException(cause);
                if (fromCause != null) return fromCause;
            }

        } catch (Exception ex) {
            logger.log(Level.FINE, "Error while inspecting exception: " + ex.getMessage(), ex);
        }
        return null;
    }

    // Attempts to parse common Retry-After values: numeric seconds, numeric ms, or HTTP-date string
    private static Long parseRetryAfterValue(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number) {
                long seconds = ((Number) v).longValue();
                return seconds <= 0 ? null : seconds * 1000L;
            }
            String s = String.valueOf(v).trim();
            // If it's a plain number (seconds)
            if (s.matches("^\\d+$")) {
                try {
                    long seconds = Long.parseLong(s);
                    return seconds * 1000L;
                } catch (NumberFormatException nfe) {
                    // ignore
                }
            }
            // Try parsing as HTTP-date (RFC 1123)
            if (looksLikeHttpDate(s)) {
                try {
                    ZonedDateTime date = ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME);
                    long ms = date.toInstant().toEpochMilli() - System.currentTimeMillis();
                    return ms > 0 ? ms : null;
                } catch (Exception ex) {
                    // ignore parse failures
                }
            }
        } catch (Exception ex) {
            // ignore
        }
        return null;
    }

    private static boolean looksLikeHttpDate(String s) {
        if (s == null) return false;
        // Rough heuristic: contains comma and a year-like token
        return s.contains(",") && s.matches(".*\\d{4}.*");
    }

}