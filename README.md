# pgrep

[![Build and test](https://github.com/finominfo/pgrep/actions/workflows/ci.yml/badge.svg)](https://github.com/finominfo/pgrep/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A parallel text search tool for plain-text files and compressed archives, written in Java. Search many files for several literal expressions in one run, with source filenames and line numbers in the results.

This project is unrelated to the Unix `pgrep` command for finding processes. It provides a focused subset of text-search functionality, not a drop-in replacement for GNU grep.

## Features

- Searches files recursively with a fixed-size worker pool and bounded in-flight tasks.
- Reads plain UTF-8 text, ZIP entries and GZIP streams incrementally.
- Supports case-sensitive literal expressions, additional required terms and exclusions.
- Keeps archive identity, such as `logs.zip!server/app.log`.
- Streams results through temporary files instead of accumulating all matches in heap memory.
- Writes UTF-8 TSV output only after every input file has been searched successfully.
- Has no third-party runtime dependencies.

## Build

Building requires JDK 17–25. The checked-in Gradle 9.1.0 wrapper downloads Gradle automatically and verifies its distribution checksum. The generated JAR runs on Java 8 or later.

Linux/macOS:

```sh
./gradlew clean build
```

Windows PowerShell:

```powershell
.\gradlew.bat clean build
```

The executable JAR is `build/libs/pgrep-2.0.0.jar`. Test reports are in `build/reports/tests/test/index.html`. Set `JAVA_HOME` to your JDK if Java is not already configured.

## Quick start

After building, run the included example from the repository root:

```sh
java -jar build/libs/pgrep-2.0.0.jar --input examples/input --patterns examples/ids.txt --config examples/pgrep.properties --output build/example.tsv
```

The output should match [`examples/expected.tsv`](examples/expected.tsv):

```text
source          line  expression  text
application.log 1     ERROR       ERROR payment declined
application.log 3     WARN        WARN invoice delayed
```

Actual columns are separated by tabs. For your own data:

```sh
java -jar build/libs/pgrep-2.0.0.jar --input /path/to/logs --patterns /path/to/ids.txt --output result.txt
```

Quote paths containing spaces. Use `--help` to display the command-line options.

With no arguments, pgrep searches `./zip`, reads `./ids.txt`, optionally loads `./pgrep.properties`, and writes `./result.txt`. Paths are relative to the working directory. An explicitly supplied `--config` file must exist.

## Search expressions

The pattern file is UTF-8, with one expression per line:

```text
ERROR
WARN
***payment
***invoice
---healthcheck
```

The rules above match lines containing `ERROR` or `WARN`, provided they also contain `payment` or `invoice` and do not contain `healthcheck`.

- Ordinary expressions are alternatives: any one may match.
- `***` introduces an additional condition. If present, at least one of these terms must occur in the same line.
- `---` excludes a line if any exclusion term occurs. Exclusions take precedence.
- Matching is case-sensitive and literal: `a.b` matches those three characters, not a regular expression.
- Blank lines are ignored; identical expressions are deduplicated.
- Outer whitespace is trimmed. Double quotes preserve intentional leading/trailing spaces: `" ERROR "`. Quote the entire expression to search for a literal prefix such as `"---literal"`.
- Empty expressions, including `""`, bare `***` and bare `---`, are rejected. At least one ordinary expression is required.
- Each matching line is emitted once per matching ordinary expression, even if that expression occurs several times in the line.

## Configuration

See [`examples/pgrep.properties`](examples/pgrep.properties):

```properties
max-threads=4
max-files=30
max-size=200000000
max-line-chars=1048576
```

- `max-threads`: maximum concurrent file searches; default 4. Entries within one archive are read sequentially. Actual concurrency is at most the smaller of `max-threads` and `max-files`.
- `max-files`: maximum submitted tasks, including active tasks and completed results waiting to be merged; default 30.
- `max-size`: approximate text-buffer budget in bytes; default 200,000,000. It is **not a hard limit on JVM heap usage**. Patterns, archive metadata, I/O buffers and JVM overhead also consume memory.
- `max-line-chars`: maximum line length in UTF-16 code units. By default it is the smaller of 1,048,576 and `max-size / (16 * effective worker count)`. Explicit values must also fit that budget. Overlong lines fail the run rather than silently truncating results.

All values must be positive. Unknown properties and malformed values are errors. Reduce worker count or increase the budget to allow longer lines. Use the JVM's `-Xmx` option to set a heap limit independently.

## Input, output and errors

Input is UTF-8, with LF, CRLF or CR line endings. ZIP and GZIP signatures are detected; `.zip`, `.gz` and `.gzip` extensions are recognized case-insensitively. Legacy GZIP files renamed to `.zip` still work. Symbolic links are skipped. Nested archives are not recursively unpacked. Invalid UTF-8 and unreadable/corrupt inputs fail the run.

The output has four columns: `source`, `line` (one-based), `expression`, and `text`. Backslashes, tabs and line breaks inside fields are escaped as `\\`, `\t`, `\r` and `\n`. Sources are relative to the input directory; ZIP sources include the outer archive name. Files are emitted in task completion order, so global ordering is not guaranteed. Lines within each file retain their order.

The output directory must already exist, and the output file must be outside the input tree. Existing output is replaced after a successful search; it is **not appended**. Input failures preserve the previous output and produce exit code 1. Success, including no matches or an empty directory, returns 0. Replacement uses an atomic move when the filesystem supports it.

Temporary result chunks are created next to the output and cleaned up on normal completion or handled failure. Allow disk space for the result and temporary chunks (up to roughly two copies of the new output, plus any previous output). Forced process termination can leave `.pgrep-*` directories behind. Very large archives still require processing time and metadata memory; there is no archive expansion quota.

## Changes from 1.0-SNAPSHOT

Version 2.0 fixes first-line truncation, blank-pattern handling, source-name collisions and empty-input completion. It replaces whole-file buffering and polling with streaming workers, validates configuration, and activates the previously unused `***` filter.

The output is now TSV rather than expression-grouped text and replaces rather than appends. Scripts that consume the old output must be updated. `max-size` now controls the line-buffer budget rather than the total size of queued file contents. Internal Java classes are implementation details, not a stable library API.

## Development

Run `./gradlew test` (Windows: `.\gradlew.bat test`). Tests cover line boundaries, filters, Unicode, archive names, parallel/serial equivalence, error cleanup, configuration and output preservation. GitHub Actions builds and tests on Linux and Windows with JDK 17 and 25, then runs the documented example on Java 8.

Performance depends on storage, archive compression, line lengths, pattern count and match volume. The project makes no unmeasured speedup claim. Increase `max-threads` only when your workload benefits; searching a single archive does not parallelize its entries.

## License

[MIT](LICENSE), copyright Kálmán Kovács.
