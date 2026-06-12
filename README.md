# Reelevant SDK for Android

Analytics tracking **and** real-time personalisation for Android apps, powered by Reelevant.

## How to use

You need a `companyId` and `datasourceId` to initialise the SDK:

``` Kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate() {
        val sdk = ReelevantSDK(this, "60d2dcae87a5ca0006335a82", "6298afcc7527000300387fdf")
    }
}
```

## Analytics

### Sending events

```kotlin
val event = sdk.pageView(mapOf("lang" to "en_US"))
sdk.send(event)
```

### Current URL

When a user is browsing a page you should call the `sdk.setCurrentURL` method if you want to be able to filter on it in Reelevant.

### User identity

To identify a user, call `sdk.setUser("<user id>")` — the SDK stores the user ID on-device and sends it with every event and personalization call.

### Labels

Each event type allows you to pass additional info via `labels (Map<String, String>)` on which you'll be able to filter in Reelevant.

``` Kotlin
val event = sdk.addCart(ids = listOf("my-product-id"), labels = mapOf("lang" to "en_US"))
```

## Personalisation

The SDK can call the Reelevant runner to fetch personalised content for your app. Identity is automatically resolved from `setUser()` / device ID — no need to pass it manually.

### Configuration

Personalization parameters are optional (defaults work out of the box):

```kotlin
val sdk = ReelevantSDK(
    context = this,
    companyId = "...",
    datasourceId = "...",
    // optional — defaults below
    runnerUrl = "https://reelevant.run",
    personalizationTimeout = 5000L,
    fallback = FallbackStrategy.Empty
)
```

### Single workflow run

```kotlin
val result = sdk.run(RunOptions(
    workflowId = "wf-hero",
    entrypoint = "43a490a0"
))

when (result.body) {
    is RunContent.Json  -> renderCard((result.body as RunContent.Json).content)
    is RunContent.Html  -> loadHtml((result.body as RunContent.Html).content)
    is RunContent.Image -> displayImage((result.body as RunContent.Image).content)
    is RunContent.Empty -> showDefault()
}
```

### Multiple workflows in parallel

```kotlin
val (hero, sidebar) = sdk.runAll(listOf(
    RunOptions(workflowId = "wf-hero", entrypoint = "43a490a0"),
    RunOptions(workflowId = "wf-sidebar", entrypoint = "b7e21f3c"),
))
```

### Click tracking

Every `RunResult` includes a `redirectionUrl` (for use as a link href) and a `trackClick()` method for fire-and-forget server-side tracking:

```kotlin
// Option 1: Use redirectionUrl as a link
openUrl(result.redirectionUrl)

// Option 2: Track the click programmatically
result.trackClick()
```

### RunResult fields

| Field | Type | Description |
|-------|------|-------------|
| `status` | `Int` | HTTP status code (0 for fallback) |
| `source` | `RunSource` | `RUNNER` or `FALLBACK` |
| `body` | `RunContent` | Typed content (Json, Html, Image, or Empty) |
| `metadata` | `Map<String, Any>` | Metadata from the output node |
| `properties` | `Map<String, Any>` | Output properties |
| `runId` | `String?` | Workflow run ID for tracking |
| `executionPath` | `List<String>` | Branch IDs taken during execution |
| `redirectionUrl` | `String` | Pre-built click-through URL |

### Fallback strategies

```kotlin
// Default — returns an empty result on error
FallbackStrategy.Empty

// Throws the underlying exception
FallbackStrategy.Error

// Custom handler
FallbackStrategy.Custom { options, error ->
    RunResult(status = 0, source = RunSource.FALLBACK, body = RunContent.Empty, ...)
}
```

### Run options

| Option | Type | Description |
|--------|------|-------------|
| `workflowId` | `String` | Workflow ID |
| `entrypoint` | `String` | Entrypoint shortId |
| `userId` | `String?` | Override identity (default: auto-resolved) |
| `params` | `Map<String, String>?` | URL parameters forwarded to runner |
| `locale` | `String?` | Locale for content resolution |
| `timeout` | `Long?` | Per-call timeout override in ms |
