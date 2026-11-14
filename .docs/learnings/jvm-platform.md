---
kind: jvm-platform
description: How JDK file, process and I/O APIs behave differently by operating system, especially on Windows. Read before writing JVM code that shares a file or stream between processes.
---

<learning>
date: 2026-11-14
---
On Windows, opening a file with `java.io.FileInputStream` just after another process wrote it with `Files.write` and moved it into place with `Files.move(..., ATOMIC_MOVE)` can fail for a moment with `FileNotFoundException: ... (The process cannot access the file because it is being used by another process)`, though `Files.exists` already returned true. It is intermittent: about one open in sixty under parallel load.

A reader that waits for such a file to appear must retry the open on `FileNotFoundException`, a bounded number of times at its poll interval, rather than fail on the first.
</learning>
