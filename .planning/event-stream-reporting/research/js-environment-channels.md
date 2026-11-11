# Channels from a non-Node JS environment to plugin code in Mill's process

On Scala.js under Mill, for each JS environment other than Node: whether the test adapter's com channel works, which other transports page code can open to Mill's process, and how the environment fills the streams it hands to `RunConfig.onOutputStream`. Answers [Which channels can carry a run's event stream and console output from a non-Node JS environment under Mill?](https://github.com/jamesgorman2/scalatest/issues/40). Facts only; no channel or environment is chosen or recommended.

Other notes hold facts this one relies on and does not repeat:

- `.planning/event-stream-reporting/research/js-native-channels.md`, cited as `JNC`: the Node answer.
- `.planning/event-stream-reporting/research/js-native-structure-and-capture.md`, cited as `JSN`: runs, channels and capture on Scala.js.

## Short answer

- **Environments.** Mill `1.1.10` offers five besides Node: `JsDom`, `ExoegoJsDomNodeJs`, `Phantom`, `Selenium` and `Playwright` (§ 1).
- **jsdom (`JsDom`).** A Node process running the page in a jsdom window. It uses Node's own com channel, so the `Logger` reaches Mill in order as on Node, and the streams are the Node process's pipes, as on Node (§ 2.1). Window code has no `require`, so it has no Node socket. jsdom's own `WebSocket` and `XMLHttpRequest` make real connections, and a synchronous `XMLHttpRequest` blocks until the server replies (§ 2.2). Every window `console` method reaches Node's `console`. `log`, `info` and `debug` go to stdout, and `error`, `warn` and `trace` to stderr (§ 2.3).
- **jsdom with Node APIs (`ExoegoJsDomNodeJs`).** As `JsDom`, but window code also gets Node's `require`, so every Node channel in `JNC` § 2–3 is open to it (§ 2.2). It calls `virtualConsole.sendTo`, which jsdom `27.0.0` no longer has. *Inference:* it fails to start with jsdom 27 or later (§ 2.3).
- **PhantomJS (`Phantom`).** The com channel is a WebSocket from the page to a Jetty 8 server in Mill's process. Jetty is a `provided` dependency, and Mill adds none. *Inference:* the com channel, and so the test adapter, cannot start under Mill as configured (§ 3.1). *Inference:* every page console message that `onConsoleMessage` receives reaches the PhantomJS process's stdout through `console.log`. Which messages it receives was not established (§ 3.3).
- **Selenium and Playwright.** No pipe exists. An injected script buffers `scalajsCom.send`, `console.log` and `console.error` in page arrays. Every 100 ms the env fetches and clears them, through `executeScript` under Selenium or `page.evaluate` under Playwright. It writes the console lines into `PipedOutputStream`s whose input ends it handed to `onOutputStream` (§ 4.1, § 4.3).
  - Com messages are fetched in push order and dispatched in list order on one thread. *Inference:* the `Logger` reaches Mill's adapter in the order the run calls it, complete up to the last fetch (§ 4.1).
  - Only `console.log` and `console.error` are captured. Playwright writes both to the stdout stream (§ 4.3).
  - *Inference:* what the page buffered after the last fetch began is dropped when the adapter closes the run (§ 4.4).
  - *Inference:* under Playwright, every received message is dispatched on a cats-effect runtime thread, which need not carry the test task's streams (§ 4.1).
- **`onOutputStream` wrapper.** Every environment invokes the callback with both streams when neither is inherited. jsdom invokes it on the starting thread, as Node does (§ 2.4). PhantomJS invokes it from Jetty's started callback, on a thread not established (§ 3.4). Selenium invokes it on the starting thread. Playwright invokes it later, on a cats-effect blocking-pool thread (*general*, § 4.3).
- **What `JNC` §§ 2–5 relies on that each environment lacks.**
  - `JsDom`: `require`, so the Node socket and file, and *inference:* `jsEnvConfig`'s `env` and `args` in window code (§ 2.2).
  - `ExoegoJsDomNodeJs`: nothing, with jsdom before 27 (§ 2.1, § 2.2, § 2.3, § 2.4).
  - `Phantom`: Jetty, so *inference:* the com channel and the `Logger` (§ 3.1). `env` and `args` in page code (§ 3.2).
  - `Selenium` and `Playwright`: process pipes, since `onOutputStream` receives `PipedInputStream`s (§ 4.3). A VM that exits when the com socket closes, since `close()` tears the browser down (§ 4.2, § 4.4). `env` and `args` in page code (§ 1). Under Playwright, the test task's thread for `Logger` calls (*inference*, § 4.1) and for `onOutputStream` (*general*, § 4.3).
  - Whether `Phantom`, `Selenium` and `Playwright` page code has `require` or a filesystem was not established (§ 5).

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`), the version `JNC` pins |
| `[sjs]` | <https://github.com/scala-js/scala-js> | tag `v1.20.2` (`6af939aab0be02540dbaf29d8bc01e509fd86aa3`), the version `JNC` pins |
| `[sjs1]` | <https://github.com/scala-js/scala-js> | tag `v1.0.0` (`e6a24304c00cf66cb79627c23a53456d3ab8b7be`), the `scalajs-env-nodejs` version both jsdom envs depend on |
| `[envs]` | <https://github.com/scala-js/scala-js-js-envs> | tag `v1.4.0` (`ef4576137227545ce4f534c935b7de1690f33db5`); see below for why not `v1.5.0` |
| `[jsdomenv]` | <https://github.com/scala-js/scala-js-env-jsdom-nodejs> | tag `v1.1.1` (`1bef3aeba3c9d2811604c808d6d1a1077ba54433`), the version `[mill] mill-build/src/millbuild/Deps.scala` lines 35–36 pin |
| `[exoego]` | <https://github.com/exoego/scala-js-env-jsdom-nodejs> | tag `v2.1.0` (`f25484b3bc2c76e73228a5a5594ba54189602441`), pinned at `Deps.scala` lines 37–38 |
| `[phantom]` | <https://github.com/scala-js/scala-js-env-phantomjs> | tag `v1.0.0` (`f817479516f4d71b5ff120064553feb978edc94b`), pinned at `Deps.scala` lines 40–41 |
| `[selenium]` | <https://github.com/scala-js/scala-js-env-selenium> | tag `v1.1.1` (`6a6dabde5066add65ecb627e4a961244ae40477b`), pinned at `Deps.scala` lines 42–43 |
| `[playwright]` | <https://github.com/ThijsBroersen/scala-js-env-playwright> | tag `v0.2.3` (`3f47a178f8e809f3eb0bddc5c118857f31247ac6`), pinned at `Deps.scala` lines 44–45 |
| `[jsdom]` | <https://github.com/jsdom/jsdom> | tag `v27.0.0` (`f2a505d5b8a91d8deb29847b2b028c334aec53df`). Mill does not pin jsdom: the env calls `require("jsdom")` in the user's Node. `[jsdomenv]` tests against `^27.0.0` (`jsdom-27.0.0/package.json`) |
| `[node]` | <https://github.com/nodejs/node> | tag `v22.21.1` (`6ac4ab19ad02803f03b54501193397563e99988e`), the reference version `JNC` uses |
| `[seleniumhq]` | <https://github.com/SeleniumHQ/selenium> | tag `selenium-3.141.59` (`e82be7d3584062a0d16af8d562d387e3d4855aa1`), the `selenium-server` version `[selenium] build.sbt` line 80 depends on |
| `[pw]` | <https://github.com/microsoft/playwright> | tag `v1.54.0` (`3da07a7049284e0c89ae65c95b955abad95e9f57`), the Playwright Java version `[playwright] build.sbt` line 52 depends on: `docs/src/api/` |
| `[jdk]` | <https://github.com/openjdk/jdk21u> | tag `jdk-21.0.5-ga`, the reference version `JNC` uses |
| `[fetch]` | <https://fetch.spec.whatwg.org/> | WHATWG living standard, read 2026-11-06 |

Paths are relative to each source's root. Abbreviations:

- `[mill]`:
  - `SJM` and `SJWI` as in `JNC`.
  - `JEC` is `libs/scalajslib/src/mill/scalajslib/api/JsEnvConfig.scala`.
  - `JV/` is `libs/scalajslib/worker/1/src/mill/scalajslib/worker/jsenv/`.
- `[sjs]`: `TA/` is `test-adapter/src/main/scala/org/scalajs/testing/adapter/`.
- `[envs]`: `JE/` is `js-envs/src/main/scala/org/scalajs/jsenv/`.
- `[sjs1]`: `NE1/` is `nodejs-env/src/main/scala/org/scalajs/jsenv/nodejs/`.
- `[jsdomenv]` and `[exoego]`: `JD` is the env's one source file, `jsdom-nodejs-env/src/main/scala/<package path>/JSDOMNodeJSEnv.scala`.
- `[phantom]`: `PJ/` is `phantomjs-env/src/main/scala/org/scalajs/jsenv/phantomjs/`.
- `[selenium]`: `SE/` is `seleniumJSEnv/src/main/scala/org/scalajs/jsenv/selenium/`.
- `[playwright]`: `PW/` is `src/main/scala/jsenv/playwright/`.

**Which library versions load.** `scalaJSLinkerClasspath` resolves `scalajs-sbt-test-adapter_2.13` at the module's `scalaJSVersion` plus the one env artifact `scalaJSJsEnvMvnDeps` picks, bound to Mill's own Scala version (`SJM` lines 69–86, 88–115). The worker module depends on the env artifacts only at compile time (`[mill] libs/scalajslib/package.mill` lines 82–94). *Inference, resolution not run:*

- `scalajs-js-envs` resolves to `1.4.0` for every non-Node environment, since no env asks for more than the test adapter does (`JNC` § Sources): `[jsdomenv]` `1.0.0`, `[exoego]` `1.0.1`, `[phantom]` `1.0.0`, `[selenium]` `1.1.1` (`build.sbt` line 81), `[playwright]` `1.4.0` (`build.sbt` line 53). `JNC` § Sources gives `1.4.0`'s two differences from `1.5.0`.
- Under both jsdom envs, `scalajs-env-nodejs` resolves to the `1.0.0` or `1.0.1` that the env depends on (`[jsdomenv]` and `[exoego]` `build.sbt`, `scalajs-env-nodejs % scalaJSVersion`, with `project/plugins.sbt` at `1.0.0` and `1.0.1`), not to Node's `1.5.0`. Its `ComSupport.scala` differs from `[envs]` `v1.5.0`'s only in the loopback address. The JVM binds `InetAddress.getByName(null)`, and Node calls `connect($port)` with no host (`NE1/ComSupport.scala` lines 212, 251).
- The Playwright dependency has no `withDottyCompat` (`Deps.scala` lines 44–45), so it resolves the `_3` artifact. `[playwright]` is built with Scala `3.7.2` and depends on Playwright Java `1.54.0`, cats-effect `3.6.3` and scribe `3.17.0` (`build.sbt` lines 14, 52–58).

No experiment was run for this note. Every citation was opened at the version above. A statement marked *inference* follows from the code read and was not observed. A statement marked *general* is operating-system, JDK, browser or library behaviour that no source here was opened for. § 5 lists what could not be determined.

## 1. The environments Mill can select

`JsEnvConfig` is a sealed trait with six cases, and `SJWI` `jsEnv` maps each to one `JSEnv` (`JEC` lines 7–15; `SJWI` lines 229–242):

| `JsEnvConfig` | Fields | `JSEnv` built | Built in |
|---|---|---|---|
| `JsDom` | `executable`, `args`, `env` | `org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv` from `[jsdomenv]` | `JV/JsDom.scala` lines 5–13 |
| `ExoegoJsDomNodeJs` | `executable`, `args`, `env` | `net.exoego.jsenv.jsdomnodejs.JSDOMNodeJSEnv` from `[exoego]` | `JV/ExoegoJsDomNodeJs.scala` lines 5–13 |
| `Phantom` | `executable`, `args`, `env`, `autoExit`, none defaulted | `PhantomJSEnv` from `[phantom]`, without `autoExit` or `jettyClassLoader` | `JV/Phantom.scala` lines 5–13 |
| `Selenium` | `capabilities`: Chrome or Firefox `headless`, or Safari | `new SeleniumJSEnv(capabilities)` from `[selenium]`, default `Config` | `JV/Selenium.scala` lines 6–23 |
| `Playwright` | Chrome, Firefox or WebKit: `headless`, `showLogs`, `debug`, launch options or Firefox prefs | `PlaywrightJSEnv.chrome`, `firefox` or `webkit` from `[playwright]`, default `Config` | `JV/Playwright.scala` lines 5–28 |

- Mill's default Chrome and WebKit launch options include `--disable-web-security` and `--allow-file-access-from-files` (`JEC` lines 136–143, 229–235). Its default Firefox prefs set `security.file_uri.strict_origin_policy` to `false` (lines 176–181).
- Selenium's and Playwright's default `Config` writes the page and scripts to temporary files and loads them by `file:` URL (`SE/SeleniumJSEnv.scala` lines 56–59; `SE/FileMaterializers.scala` lines 53–64; `PW/PlaywrightJSEnv.scala` lines 72–74; `PW/FileMaterializers.scala` lines 62–73).
- No case carries anything that reaches a browser page's code, other than through the framework arguments (`JNC` § 2.1).

## 2. jsdom: `JsDom` and `ExoegoJsDomNodeJs`

Both envs run `executable :: args` through `ExternalJSRun`, with `NODE_MODULE_CONTEXTS=0` plus `env`, and feed the program on stdin (`JD` lines 63–72 in `[jsdomenv]`, 65–74 in `[exoego]`). The program creates a `JSDOM` at `http://localhost/` with `runScripts: "dangerously"` and appends each Scala.js script to the window as a `<script src>` (`[jsdomenv] JD` lines 113–133; `[exoego] JD` lines 106–128).

### 2.1 The com channel and the `Logger`

- Both call `ComRun.start` from `scalajs-env-nodejs`, prepending its setup script to the program (`[jsdomenv] JD` lines 44–51; `[exoego] JD` lines 43–50). The setup runs in Node's global scope, and the program copies `global.scalajsCom` onto the window (`[jsdomenv] JD` line 126; `[exoego] JD` lines 119–121).
- So `JNC` § 2.1 (connect, `process.exit(0)` when the com socket closes), § 2.3 and § 4.1 (where `Logger` calls run and land) hold unchanged, subject to the loopback difference above.

### 2.2 Other transports

- **`JsDom`.** Window code has no `require` (`JSN` § 2.3), so no Node socket or file.
- **`ExoegoJsDomNodeJs`.** The program copies `global.require` onto the window (`[exoego] JD` lines 119–121). Node defines `globalThis.require` for a program read from stdin, which it evaluates as CommonJS by default (`[node] lib/internal/main/eval_stdin.js` lines 23–57; `lib/internal/process/execution.js` lines 433–440). So window code can `require('net')` and `require('fs')`, and `JNC` § 2.1 and § 3 apply to it. *Inference:* `require('process').env` gives it `jsEnvConfig`'s `env`.
  - This contradicts `JSN` § Short answer and § 2.3, which say jsdom runs have no filesystem. `JSN` § 2.3 cites only `[jsdomenv]`'s env, for which it holds (`JsDom`, above).
- **jsdom's own APIs, both envs.**
  - jsdom's `WebSocket` opens a real connection through the `ws` package, sending the window's origin (`[jsdom] lib/jsdom/living/websockets/WebSocket-impl.js` lines 129–136). Its `send` hands data to `ws` and decrements `bufferedAmount` in the completion callback (lines 285–310).
  - jsdom's `XMLHttpRequest` applies CORS against the window's origin, `http://localhost` (`lib/jsdom/living/xhr/xhr-utils.js` lines 62–78).
  - A synchronous `XMLHttpRequest` runs the request in a child Node process through `spawnSync` and returns once it completes (`lib/jsdom/living/xhr/XMLHttpRequest-impl.js` lines 577–592).
  - *Inference:* a `WebSocket` message still queued in `ws` when the com socket closes is lost by `process.exit(0)`, as a Node socket write is (`JNC` § 2.1). A synchronous request has completed before the call returns.
- **The address.** The framework arguments reach the runner in the window (`JNC` § 2.1). `jsEnvConfig`'s `env` and `args` reach the Node process. *Inference* (`JSN` § 2.3): they do not reach `JsDom` window code.

### 2.3 Console output

- Each window `console` method emits an event of the same name on the window's virtual console (`[jsdom] lib/jsdom/browser/Window.js` lines 956–981). `forwardTo(console)` adds a listener per method that calls Node's `console` method of that name (`lib/jsdom/virtual-console.js` lines 14–22). Node's `EventEmitter` calls listeners synchronously (*general*).
- Node's `console.log`, `info` and `debug` print to stdout, and `console.error`, `warn` and `trace` to stderr (`[node] doc/api/console.md` lines 300, 346, 403, 414, 537, 565).
- **`JsDom`** calls `forwardTo` when the virtual console has it, else `sendTo` (`[jsdomenv] JD` lines 84–88).
- **`ExoegoJsDomNodeJs`** calls only `sendTo`, after checking that `jsdom.JSDOM` exists (`[exoego] JD` lines 87–90). jsdom `27.0.0` renamed `sendTo()` to `forwardTo()` (`[jsdom] Changelog.md` line 39), and its `VirtualConsole` defines only `forwardTo` (`virtual-console.js` lines 4–46). *Inference:* with jsdom 27 or later, the program throws `TypeError` before any test code loads.
- A `jsdomError`, which includes an uncaught exception in window code, makes both envs print it and call `process.exit(1)` (`[jsdomenv] JD` lines 89–111; `[exoego] JD` lines 91–104). *Inference:* stdout still queued in Node is then lost as `JNC` § 5.1 describes.
- From Node's stdout and stderr onward, `JNC` § 5.1 holds unchanged: pipes, `PipeOutputThread`, and loss of queued POSIX writes at `process.exit`.

### 2.4 An `onOutputStream` wrapper

Both envs validate with `ExternalJSRun.supports` (`[jsdomenv] JD` line 144; `[exoego] JD` line 142) and start through `ExternalJSRun`, so a wrapper receives both pipes as on Node (`JNC` § 5.1).

## 3. PhantomJS: `Phantom`

### 3.1 The com channel

- `startWithCom` calls the env's own `ComRun.start(config.jettyClassLoader, …)` (`PJ/PhantomJSEnv.scala` lines 36–43). `ComRun` loads `JettyWebsocketManager` through `jettyClassLoader`, or through its own class loader when that is `null` (`PJ/ComRun.scala` lines 39–63). Mill's default is `null`, and Mill sets none (`PJ/PhantomJSEnv.scala` lines 290–297; `JV/Phantom.scala` lines 5–13).
- `JettyWebsocketManager` uses Jetty 8's server and WebSocket classes (`PJ/JettyWebsocketManager.scala` lines 11–17, 29–44). The env declares both Jetty artifacts `provided` (`[phantom] build.sbt` lines 79–80). Its sbt plugin supplies them in a private configuration and class loader (`phantomjs-sbt-plugin/src/main/scala/org/scalajs/jsenv/phantomjs/sbtplugin/PhantomJSEnvPlugin.scala` lines 35–44, 58–70, 87–103).
- Mill resolves only the env artifact (§ Sources), and no Jetty appears in Mill's Scala.js build or module code (`[mill] libs/scalajslib`, `mill-build`).
- *Inference:* `startWithCom` throws a `LinkageError` under Mill as configured, so the test adapter cannot run on PhantomJS. A module that overrides `scalaJSJsEnvMvnDeps`, a public task, could add Jetty to the tools class path (`SJM` line 69).
  - This contradicts `JSN` § 3.1 W3, which calls the `Logger` "the only channel that reaches the JVM everywhere". No experiment settled which holds.
- **If Jetty is present:**
  - The page's setup script opens `new WebSocket("ws://localhost:<port>")` to a Jetty server bound to `localhost` on an ephemeral port (`PJ/ComRun.scala` lines 289–375; `PJ/JettyWebsocketManager.scala` lines 29–32).
  - `scalajsCom.send` buffers until the socket opens, sends each message in 32 KB fragments, and drops messages once the socket has closed (`PJ/ComRun.scala` lines 300–374).
  - When the socket closes, the page calls `window.callPhantom({action: 'exit'})`, and the launcher calls `phantom.exit(0)` (lines 311–314; `PJ/PhantomJSEnv.scala` lines 153–163).
  - On the JVM, Jetty passes each fragment to `receiveFrag`, which is `synchronized` and calls `onMessage` on each completed message (`PJ/ComRun.scala` lines 175–199). The thread Jetty calls it on was not examined.

### 3.2 Other transports

- *Inference:* page code can open its own `WebSocket` to Mill's process, as the setup script does.
- The page loads from a `file:` URL (`PJ/PhantomJSEnv.scala` lines 126–137, 179–198). Whether PhantomJS allows an `XMLHttpRequest` from it to `http://localhost` was not established.
- The address reaches page code only through the framework arguments. `env` and `args` go to the `phantomjs` process (`PJ/PhantomJSEnv.scala` lines 55–64).

### 3.3 Console output

- The launcher, which runs in PhantomJS's own context, sets `page.onConsoleMessage = function(msg) { console.log(msg); }` (`PJ/PhantomJSEnv.scala` lines 138–140). *Inference:* every page console message the callback receives reaches PhantomJS's stdout as one line, `console.error` included, with no stderr. Which page console methods PhantomJS passes to `onConsoleMessage` was not established.
- A page error prints to `console.error` and exits with status 2 (lines 141–152).
- The env logs two `debug` lines per run through `runConfig.logger` (lines 173–174, 194–195). Where Mill's adapter logger prints them: `JNC` § 1.1.

### 3.4 An `onOutputStream` wrapper

The run is an `ExternalJSRun` validated by `ExternalJSRun.supports` (`PJ/PhantomJSEnv.scala` lines 55–64, 261), so the pipes reach `onOutputStream` as in § 2.4. `ComRun` starts it from Jetty's "started" callback (`PJ/ComRun.scala` lines 63–78). Whether that callback runs on the thread that calls `mgr.start()` was not established.

## 4. Browsers: `Selenium` and `Playwright`

Both envs inject the same setup script ahead of the Scala.js scripts and poll the page every 100 ms. They differ in where output goes and on which threads.

### 4.1 The com channel and the `Logger`

- **In the page.**
  - The setup script wraps `console.log` and `console.error`. Each call joins `String(arg)` of its arguments with spaces, pushes the line onto `consoleLog` or `consoleError`, and then calls the original.
  - `window` `error` events are pushed onto `errors`.
  - `scalajsCom.send` pushes onto `outMessages`.
  - `fetch()` returns copies of the four arrays and empties them (`SE/JSSetup.scala` lines 15–90; `PW/JSSetup.scala` lines 16–90).
- **Selenium.**
  - `SeleniumRun` starts one task on a new single-thread executor. Once the interface is up, it loops `sendAll()`, `fetchAndProcess()` and `Thread.sleep(100)` while `wantClose` is false (`SE/SeleniumRun.scala` lines 27–40).
  - `fetchAndProcess` calls `executeScript("return …fetch();")`. It prints each `consoleLog` line to the out stream, then each `consoleError` line to the err stream, then calls `onMessage` for each message, then throws if `errors` is non-empty (lines 50–67).
  - `send` queues on the JVM, and `sendAll` passes each queued message to the page by `executeScript` (lines 87–100).
  - `executeScript` runs its script "as the body of an anonymous function" and returns a JS array as a `List` and an object as a `Map` (`[seleniumhq] java/client/src/org/openqa/selenium/JavascriptExecutor.java` lines 32–65).
- **Playwright.**
  - `processUntilStop` repeats `sendAll`, `page.evaluate("…fetch();")` and `streamWriter`, each followed by a 100 ms wait, until the stop flag is set (`PW/ResourcesFactory.scala` lines 44–73).
  - `streamWriter` calls `onMessage` for each message first. It then prints the `consoleLog` lines, then the `errors`, then the `consoleError` lines, all to the out stream, and throws if `errors` is non-empty (lines 118–140).
  - The run is a cats-effect `Resource` started by `unsafeToFuture` on the lazy `future` (`PW/CEComRun.scala` lines 30–31; `PW/Runner.scala` lines 45–84). `JSEnvRPC` reads `run.future` in its constructor (`TA/JSEnvRPC.scala` lines 46, 59–60).
- **Order.** Messages are fetched in push order and dispatched in list order on one thread. *Inference:* the `Logger` reaches Mill's adapter in the order the run calls it, and complete up to the last fetch, which is what the `JSEnv` contract requires (`JE/JSEnv.scala` lines 61–81). Each message waits up to 100 ms plus one round trip.
- **Thread.** The adapter dispatches synchronously on the thread that calls `onMessage` (`JSN` § 2.1), and Mill's logger writes to that thread's streams (`JNC` § 4.1).
  - *Inference:* Selenium's executor thread is created by the first task submission, which runs in the `SeleniumRun` constructor on the starting thread, so it inherits the test task's streams.
  - *Inference:* Playwright's `IO` steps run on cats-effect's global runtime threads. Those threads inherit the streams of whichever thread created them, and the runtime lives as long as the tools class loader, so they need not inherit this test task's streams.

### 4.2 Other transports

- Page code can open a `WebSocket` to Mill's process. Fetch sets a `websocket`-mode request's response tainting to "basic", so no CORS check applies, and sends an `Origin` header ([fetch § main fetch](https://fetch.spec.whatwg.org/#main-fetch), [§ origin header](https://fetch.spec.whatwg.org/#origin-header)).
- *General:* each browser's mixed-content and local-network rules for a `file:` page connecting to loopback are not covered here.
- An `XMLHttpRequest` or `fetch` from the `file:` page is cross-origin. Whether Mill's default `--disable-web-security` (§ 1) lifts that check in Chromium and WebKit was not established from a primary source.
- The address reaches page code only through the framework arguments (§ 1).
- **Teardown.**
  - Selenium calls `driver.quit()` when the polling task ends, unless `keepAlive` (`SE/SeleniumRun.scala` lines 42–46, 164–165), which "Quits this driver, closing every associated window" (`[seleniumhq] java/client/src/org/openqa/selenium/WebDriver.java` lines 133–136).
  - Playwright releases the page, browser and Playwright in reverse order of acquisition (`PW/PageFactory.scala` lines 14–80). `Browser.close` "closes the browser and all of its pages" and is "similar to force-quitting the browser" (`[pw] docs/src/api/class-browser.md` lines 89–100).
  - *Inference:* a page `WebSocket`'s unsent `bufferedAmount` is lost at teardown.

### 4.3 The streams and an `onOutputStream` wrapper

- Both validate `supportsInheritIO` and `supportsOnOutputStream` (`SE/SeleniumRun.scala` lines 109–113; `PW/PlaywrightJSEnv.scala` lines 27–28).
- `OutputStreams.prepare` makes a `PipedInputStream`/`PipedOutputStream` pair per non-inherited stream and calls `onOutputStream` with the input ends. It then wraps the output ends in `PrintStream`s (`SE/OutputStreams.scala` lines 15–35; `PW/OutputStreams.scala` lines 15–33). A wrapper that replaces `onOutputStream` therefore receives both streams.
- **What the streams carry.**
  - Selenium: `console.log` lines on out, `console.error` lines on err.
  - Playwright: `console.log`, `console.error` and `window` error messages, all on out. Err carries nothing.
  - Neither carries `console.warn`, `info`, `debug` or `trace`, nor anything the browser writes outside the page.
  - Within each stream, lines keep page order. Across a fetch, Selenium writes stdout lines before stderr lines and before that fetch's messages, and Playwright writes them after the messages (§ 4.1).
- **Where `onOutputStream` runs.**
  - Selenium calls `prepare` in `startInternal`, on the starting thread, after navigating to the page (`SE/SeleniumRun.scala` lines 129–151).
  - Playwright calls it inside `IO.blocking`, after launching the browser and navigating (`PW/ResourcesFactory.scala` lines 103–116; `PW/Runner.scala` lines 59–75). That is a cats-effect blocking-pool thread, after `startWithCom` has returned (*general*). *Inference:* Mill's `PipeOutputThread`s are then created on that thread and inherit its streams (`JNC` § 5.1).
- **Back-pressure.** A `PipedInputStream` holds 1024 bytes by default, and a writer waits while it is full (`[jdk] src/java.base/share/classes/java/io/PipedInputStream.java` lines 64, 272–281). *Inference:* if nothing drains a stream, the polling thread blocks, and with it com delivery.

### 4.4 The end of a run

- **Selenium.** `close()` sets `wantClose` and returns (`SE/SeleniumRun.scala` line 48). The loop ends at its next check, without another fetch. Then the env quits the driver, closes the streams and closes the materializer (lines 35–46).
- **Playwright.** `close()` sets the stop flag and waits up to 5 seconds for the resources to be released (`PW/Runner.scala` lines 99–107). The loop ends after its next 100 ms wait, without another fetch (`PW/ResourcesFactory.scala` line 70).
- When Mill calls `close()`: `JNC` § 5.1.
- *Inference:* console lines and com messages the page buffered after the last fetch began are dropped. With one fetch every 100 ms, that covers up to about the last 100 ms of page activity before `close()`.
- Closing the `PipedOutputStream`s gives Mill's `PipeOutputThread`s end of stream once they have read what was written.

## 5. What was not determined

- Whether each *inference* above holds when run. In particular:
  - that Phantom's com channel fails under Mill for want of Jetty (§ 3.1);
  - that `ExoegoJsDomNodeJs` fails with jsdom 27 or later (§ 2.3);
  - which streams Playwright's cats-effect threads write Mill's `Logger` lines and `PipeOutputThread` output to (§ 4.1, § 4.3);
  - how much page output is dropped at `close()` in practice (§ 4.4).
- Which library versions Mill's resolver actually loads for each environment (§ Sources).
- Whether the `nodejs-env` `1.0.0` com channel, whose JVM end binds `InetAddress.getByName(null)` while Node connects to `localhost` with no host, connects on hosts where `localhost` resolves to IPv6 first (§ Sources).
- Which page console methods PhantomJS passes to `onConsoleMessage`. Whether `phantom.exit` flushes pending stdout. Which thread Jetty runs the started callback and message callbacks on (§ 3).
- Whether `Phantom`, `Selenium` and `Playwright` page code has `require`, a Node socket or a filesystem. No source here was opened for it. `JSN` § 2.3 states that a Selenium run has no filesystem.
- Whether PhantomJS, Chromium, Firefox and WebKit allow a `file:` page to reach `http://` or `ws://` on loopback under Mill's default options (§ 3.2, § 4.2).
- What the browser driver, the Playwright driver process and the envs' own logging (scribe, under `showLogs` and `debug`) write, and whether it reaches Mill's console outside the run's streams (§ 4).
- Whether any of this holds on versions other than those in § Sources, or with jsdom versions other than `27.0.0`.
