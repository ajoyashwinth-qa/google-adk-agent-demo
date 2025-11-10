google-adk-agent demo
======================

Overview
--------
This repository contains a small demo showing how to run a simple Google ADK agent (Java + Spring Boot) that calls the Google GenAI models via the Google ADK client. The sample agent is a weather advisor that explains the forecast for the next 3 days and asks a follow-up question.

This project includes:
- `agent_action.java` — main Spring Boot entry that registers the `LlmAgent` with ADK and starts the web UI/server.
- `RetryUtils.java` — a small reusable helper that implements client-side retries with exponential backoff, jitter, and optional honoring of Retry-After if the GenAI API exposes it.
- `application.properties` — enables debug logging for the GenAI client and HTTP transport so you can inspect headers and raw responses when troubleshooting.

Why this exists
----------------
You saw `503: The model is overloaded` errors when invoking GenAI. Those are server-side capacity errors (model endpoints busy). This repo demonstrates two levels of mitigation:

1. Retry + backoff for transient server-side 503/429 responses.
2. Fallback model list and reduced concurrency (you can configure these) so your app is less likely to overload a single model.

Prerequisites
-------------
- Java 18 installed and available on PATH.
- Maven 3.8+ installed.
- A Google AI Studio / GenAI API key set in the `GOOGLE_API_KEY` environment variable.
- An account with adequate quota/billing enabled for the models you want to use.

Setting the environment variable (Windows cmd.exe)
-------------------------------------------------
Run in cmd.exe:

```cmd
setx GOOGLE_API_KEY "ya29.your_api_key_here"
```

Then open a new terminal session so the variable is available to your processes.

Build
-----
From the project root run:

```cmd
mvn -DskipTests package
```

Run (development)
------------------
You can run the demo Spring Boot application which exposes a simple ADK UI and endpoints:

```cmd
mvn -DskipTests package exec:java -Dexec.mainClass=agent_action
```

How the retry behavior works
---------------------------
- `RetryUtils.executeWithRetries(Callable<T>, maxAttempts, baseDelayMs, maxDelayMs, maxJitterMs)` wraps calls that may throw `com.google.genai.errors.ServerException` (503) or an `ExecutionException` wrapping it.
- It inspects exceptions for `Retry-After` (numeric seconds or HTTP-date) via reflection; if found, that value is used (capped) for the delay before retrying.
- Otherwise it uses exponential backoff with jitter.

Notes about the free tier and 503 errors
--------------------------------------
- 503 errors mean the model endpoint is overloaded at the time you sent the request. This can happen more often on free-tier projects, because quota and priority are lower.
- The free-tier makes it more likely you'll experience capacity-related errors, but paid/production projects can also encounter 503s under heavy load.
- Mitigations:
  - Reduce concurrency (fewer parallel requests).
  - Use smaller/less-popular models where possible.
  - Implement retries with exponential backoff (done here).
  - Monitor quotas and upgrade if you need stronger SLAs.

Project structure
-----------------
- `src/main/java/agent_action.java` — main application.
- `src/main/java/RetryUtils.java` — retry helper used to wrap GenAI calls.
- `src/main/resources/application.properties` — logging and Spring configuration.
- `pom.xml` — Maven build file.

Next steps / optional improvements
--------------------------------
- Wire `RetryUtils` into each place where you call `Models.generateContent()` (or the async variant). I added the helper, but you may need to integrate it into the ADK runner codepath if you want per-request retries for interactive runs.
- Add metrics (counters for retries, failures, successes) to track when the model is overloaded.
- Add a configuration file (YAML/properties) to expose retry/fallback settings via environment variables.
- Optionally use GitHub Actions to run a build on push and run unit tests.

Pushing to GitHub
-----------------
I created a local git repository and prepared files for commit. To push this repo to GitHub follow these steps (replace `<your-username>`):

1. Create a repository on GitHub named `google-adk-agent-demo` via the website or GitHub CLI:

```cmd
:: Using GitHub website: create a new repository with name "google-adk-agent-demo"
:: Or using gh (if installed):
gh repo create <your-username>/google-adk-agent-demo --public --source=. --remote=origin --push
```

2. If you created the remote manually on GitHub, connect and push:

```cmd
git remote add origin https://github.com/<your-username>/google-adk-agent-demo.git
git branch -M main
git push -u origin main
```

Troubleshooting
---------------
- If you still see frequent 503s after adding retries and reducing concurrency, check the Google Cloud Console for quota usage and consider upgrading.
- Enable debug logs (already in `application.properties`) and inspect the HTTP responses from the GenAI client to find Retry-After headers.
- If the SDK version does not expose headers, consider implementing an HTTP interceptor or using a lower-level HTTP client to capture raw responses.

License
-------
MIT
