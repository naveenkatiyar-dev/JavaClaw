# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`AGENTS.md` at the repo root is the detailed architecture reference (task flow, agent/prompt construction, channels, config, subagents UI, onboarding). Read it first; this file only adds commands and notes that complement it.

## Commands

Gradle multi-module build (wrapper included), Java 25 required.

```bash
./gradlew :app:bootRun                      # run the app (UI on :8080, JobRunr dashboard on :8081)
./gradlew test                              # all tests (what CI runs)
./gradlew :base:test                        # one module
./gradlew :base:test --tests ai.javaclaw.tasks.TaskManagerTest          # one class
./gradlew :base:test --tests 'ai.javaclaw.tasks.TaskManagerTest.someMethod'  # one method
./gradlew app:jibDockerBuild                # build docker image (jobrunr.io/javaclaw)
```

There is no lint/format task configured.

## Notes beyond AGENTS.md

- `plugins/` also contains a `whatsapp` module (wacli CLI-based channel) that is not listed in AGENTS.md's module tree; check `settings.gradle` for the authoritative module list.
- `app` tests include Spring Modulith / ArchUnit checks and a full-context test using Testcontainers (needs Docker), so module-boundary violations and missing Docker both surface as test failures in `app`.
- Runtime config lives in `app/src/main/resources/application.private.yaml` (gitignored, mutated by onboarding and the agents UI via `ConfigurationManager`). Any config change publishes `ConfigurationChangedEvent` and restarts the whole application. The README still says `application.yaml`; AGENTS.md is correct.
- Providers and plugins are drop-in: they register through `META-INF/spring/...AutoConfiguration.imports` in their own module, not through `app`'s component scan. New providers implement `AgentOnboardingProvider` and a `ChatModelFactory`.
- The `workspace/` directory at repo root is runtime data (prompts, skills, tasks, subagent definitions) read by the agent, not source code.
