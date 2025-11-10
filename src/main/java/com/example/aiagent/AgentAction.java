package com.example.aiagent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.web.AdkWebServer;
import com.google.genai.errors.ServerException;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AgentAction {

    private static final Logger logger = Logger.getLogger(AgentAction.class.getName());

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
                    lastEx = e;
                    logger.log(Level.WARNING, "Model/service error with model " + model + " on attempt " + attempt + ": " + e.getMessage(), e);
                    suggestedDelayMs = com.example.aiagent.RetryUtils.inspectServerExceptionForDelay(e);

                } catch (Exception e) {
                    Throwable cause = (e instanceof java.util.concurrent.ExecutionException) ? e.getCause() : e;
                    if (cause instanceof ServerException) {
                        ServerException se = (ServerException) cause;
                        lastEx = se;
                        logger.log(Level.WARNING, "Model/service error (wrapped) with model " + model + " on attempt " + attempt + ": " + se.getMessage(), se);
                        suggestedDelayMs = com.example.aiagent.RetryUtils.inspectServerExceptionForDelay(se);
                    } else {
                        lastEx = e;
                        logger.log(Level.SEVERE, "Unexpected error while starting ADK server with model " + model + ": " + e.getMessage(), e);
                        suggestedDelayMs = com.example.aiagent.RetryUtils.inspectServerExceptionForDelay(e);
                    }
                }

                if (!started && attempt < attemptsPerModel) {
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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> logger.info("Shutting down Agent Action server")));
    }
}
