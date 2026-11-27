# Test plan for an unattended agent: perinkpheral-clion-plugin

This plan is for a coding agent that works alone for one or two days. Each task is small
and stands alone. Do the tasks in order. Finish one task, write its report, then start the
next.

## Rules

- Do not change `src/main/`. You may add tests under `src/test/kotlin/` in new files
  whose names end in `AgentTest.kt` only.
- Do not commit, push, tag or open pull requests.
- Do not contact devices or external services during tests. Source and toolchain
  downloads are allowed during setup; test endpoints must be local.
- Do not fix a bug you find. Report it.
- After each task, append its report to `REPORT.md` in the report folder you were given.
  If no folder was given, use `build/agent/REPORT.md`.

## Report format, for every finding

```
### <task number>.<finding number> <one-line title>
File: <path>:<line>
What happens: <exception / wrong result>
Expected: <what the code or README.md promises>
How to reproduce: <one command>
```

## Install the toolchain first

There is no device. Everything runs on this computer, in the simulator. Install these
before any task, and check each with the command after it:

- Git: `git --version`.
- Java 21 or later (Temurin is fine): `java -version`.
- A C compiler. Fuzzing additionally requires Clang with libFuzzer.
  Check: `<compiler> --version`.
- wasi-sdk 34 from https://github.com/WebAssembly/wasi-sdk/releases (the archive for this
  computer's system and processor). Unpack it and `export WASI_SDK_PATH=<its folder>`.
  Check: `$WASI_SDK_PATH/bin/clang --version`.
- Python 3.12 or later: `python3 --version`.
- Node.js 22 and npm: `node --version`.
- ffmpeg: `ffmpeg -version`.
- Optional, for checks that match the Linux CI: Docker with the `gcc:14` image.

ESP-IDF is not needed: nothing here builds or flashes device firmware.

Get the repositories side by side in one folder, from https://github.com/Perinkpheral:
`perinkpheral-os`, `perinkpheral-apps`, `perinkpheral-hacs`, `perinkpheral-dev-portal`,
`perinkpheral-clion-plugin`. The tools find each other as siblings.

Then build the tools and the simulator once and run an application in it:

```sh
cd perinkpheral-os
tools/perinkpheral                        # builds the Kotlin tools on first use, prints the commands
make -C firmware/test/host test           # host tests; needs WASI_SDK_PATH
tools/perinkpheral sim-test firmware/test/sim
tools/perinkpheral sim sdk/apps/hello     # the simulator with one application; stop it with Ctrl-C
```

All four must work before Task 1. If one fails, write the exact error into the report and
stop: do not change the repository to make it pass.

## Setup

The plugin needs a checkout of the firmware repository (usually `../perinkpheral-os`).
Gradle caches test results even when only that checkout changed, so always run:

```sh
./gradlew test --rerun-tasks -PperinkpheralPath=../perinkpheral-os
```

It must pass before you start.

## Task 1. Generated files

Read `GeneratedFilesTest.kt` and the code it tests. Add cases: an application folder
with spaces and Cyrillic in its path, a missing `strings/` folder, a manifest with every
setting type, a project without a device address. Expected: the generated CMake and
toolchain files are valid and contain no absolute path of this computer other than the
project and SDK paths.

## Task 2. Simulator sessions

Read `SimulatorSessionsTest.kt`. Add cases: starting two sessions at once, stopping one
that already exited, a simulator that prints nothing and exits at once, a port already
taken. Expected: no session leaks, every failure is shown to the user once.

## Task 3. Task processes

Read `TaskProcessHandlerTest.kt`. Add cases: a process that prints 10 MB, one that never
ends and is cancelled, one that exits with a signal, output without a final newline,
invalid UTF-8 in output.

## When all tasks are done

Write a short summary at the top of `REPORT.md`. Then stop.
