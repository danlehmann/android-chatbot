# LocalChat

A small, auditable Android chat client for OpenAI-compatible LLM servers
(llama.cpp server, vLLM, Ollama, LM Studio, DeepSeek harnesses, ...).

It is meant to sit behind an SSH tunnel: use any Android SSH client that supports
local port forwarding to reach your LLM box, then point the app at
`http://127.0.0.1:PORT/v1`.
The network security config only permits cleartext HTTP to loopback, so the app
cannot talk plain HTTP to anything else.

## Features

- Multiple servers, each with base URL, optional API key, default model,
  system prompt, temperature and a history budget.
- Model list fetched from `/v1/models`.
- Streaming replies via SSE, with a stop button.
- Reasoning / chain-of-thought (`reasoning_content`) shown collapsed.
- Conversations persisted in Room, listed in a drawer.
- Long-press a message: copy, delete, edit-and-resend (user), regenerate (last reply).
- Image attachments for vision models, from the system photo picker or the
  camera app (no camera or storage permission needed). Images are rotated
  upright, downscaled to 1568 px and sent as OpenAI-style `image_url` parts.
- Markdown rendering written in-house (headings, lists, fenced code with copy,
  inline code, bold/italic, quotes, tables, rules).
- LaTeX math: `\(...\)`, `$...$`, `\[...\]` and `$$...$$` rendered by JLaTeXMath,
  with a Unicode fallback for formulas it cannot parse.
- Context usage in the top bar: tokens used (from the server's usage report)
  over the model's window (from `/v1/models`, llama.cpp `/props`, or set per server).

## Dependencies

AndroidX Compose + Material 3, Room, OkHttp, kotlinx.serialization,
kotlinx.coroutines, and JLaTeXMath for Android (`ru.noties:jlatexmath-android`,
GPL-2.0 with the classpath linking exception, so it does not
impose the GPL on this app; offline, no permissions) for math rendering. Nothing else, no
analytics, no crash reporting.

## Build

Requires JDK 17 to 21 (Gradle 8.13 does not run on Java 25, which is what a
current Android Studio bundles).

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # markdown parser + streaming client tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and run it.

## Usage

1. In your SSH client, connect to a host that can reach your LLM server and add
   a local port forward, e.g. local `8000` to `llm-server:8000`.
2. Open the app, drawer, Servers, add one with base URL
   `http://127.0.0.1:8000/v1`, press Fetch to pick a model, and save.
3. Chat.

## Layout

```
app/src/main/java/net/daniellehmann/localchat/
  MainActivity.kt       three-screen hand-rolled navigation
  ChatViewModel.kt      state, sending, streaming, history trimming
  Prefs.kt              last used server/model
  api/OpenAiClient.kt   /v1/models and streaming /v1/chat/completions
  data/                 Room entities, DAOs, database
  ui/ChatScreen.kt      drawer, message list, input bar
  ui/ServersScreen.kt   server list and editor
  ui/Markdown.kt        parser + Compose renderer
```

## License

MIT, see [LICENSE](LICENSE).

### Third-party notice

Builds of this app include
[JLaTeXMath for Android](https://github.com/noties/jlatexmath-android)
(`ru.noties:jlatexmath-android:0.2.0`), which is licensed under the GNU GPL v2
with a linking exception that permits combining it with independently licensed
code such as this app. It is used unmodified. Its complete source code is
available at https://github.com/noties/jlatexmath-android, and the original
JLaTeXMath project at https://github.com/opencollab/jlatexmath.
