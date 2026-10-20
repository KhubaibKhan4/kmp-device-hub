package io.github.kmpdevicehub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashesTest {
    private fun feed(detector: CrashDetector, lines: List<String>, parse: (String) -> LogEntry?) =
        lines.mapNotNull(parse).mapNotNull(detector::feed).groupBy { it.id }.map { it.value.last() }

    @Test
    fun `reads JVM, Kotlin Native and Swift frames`() {
        assertEquals(StackFrame("Cart.kt", 42, "com/example/shop/Cart.kt", className = "com.example.shop.Cart"),
            StackTraces.frame("\tat com.example.shop.Cart.add(Cart.kt:42)"))
        assertTrue(StackTraces.frame("    at android.view.View.performClick(View.java:8024)")!!.isFramework)
        assertFalse(StackTraces.frame("    at com.example.shop.Cart\$add\$1.invokeSuspend(Cart.kt:7)")!!.isFramework)
        assertNull(StackTraces.frame("    at java.lang.reflect.Method.invoke(Native Method)"))

        val native = StackTraces.frame("    at 3   iosApp   0x0000000104f5a1b0   kfun:com.example.shop.Cart#add(){} + 120 (/Users/me/shop/shared/src/commonMain/kotlin/com/example/shop/Cart.kt:42:13)")
        assertEquals(StackFrame("Cart.kt", 42, "shop/Cart.kt", path = "/Users/me/shop/shared/src/commonMain/kotlin/com/example/shop/Cart.kt"), native)
        assertNull(StackTraces.frame("    at 9   iosApp   0x0000000104f5a1b0   kfun:kotlin.Throwable#<init>(){} + 12 (<compiler-generated>)"))

        assertEquals(StackFrame("ContentView.swift", 12, "iosApp/ContentView.swift"),
            StackTraces.frame("iosApp/ContentView.swift:12: Fatal error: Unexpectedly found nil while unwrapping an Optional value"))
    }

    @Test
    fun `finds an Android crash while other threads keep logging`() {
        val crashes = feed(CrashDetector(), listOf(
            "10-19 12:00:00.900  4321  4321 D Shop    : Checkout tapped",
            "10-19 12:00:01.000  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main",
            "10-19 12:00:01.000  4321  4321 E AndroidRuntime: Process: com.example.shop, PID: 4321",
            "10-19 12:00:01.000  4321  4340 I Ktor    : RESPONSE: 200 OK",
            "10-19 12:00:01.000  4321  4321 E AndroidRuntime: java.lang.IllegalStateException: Cart is empty",
            "10-19 12:00:01.000  4321  4321 E AndroidRuntime: \tat com.example.shop.CartViewModel.checkout(CartViewModel.kt:42)",
            "10-19 12:00:01.000  4321  4321 E AndroidRuntime: \tat android.view.View.performClick(View.java:8024)",
            "10-19 12:00:01.000  4321  4321 E AndroidRuntime: Caused by: java.io.IOException: timeout",
            "10-19 12:00:01.000  4321  4321 E AndroidRuntime: \t... 12 more",
            "10-19 12:00:01.100  4321  4321 I Process : Sending signal. PID: 4321 SIG: 9",
        ), LogParser::logcat)
        val crash = crashes.single()
        assertEquals("java.lang.IllegalStateException: Cart is empty", crash.title)
        assertEquals("12:00:01.000", crash.time)
        assertEquals(7, crash.trace.size)
        assertEquals(listOf(42, 8024), crash.trace.mapNotNull { it.frame?.line })
        assertEquals("    at com.example.shop.CartViewModel.checkout(CartViewModel.kt:42)", crash.trace[3].text)
        assertTrue(crash.text.startsWith("FATAL EXCEPTION: main\nProcess: com.example.shop, PID: 4321\n"))
    }

    @Test
    fun `finds Kotlin Native, Objective-C, Swift and native signal crashes`() {
        val ios = feed(CrashDetector(), listOf(
            "Loading cart",
            "Uncaught Kotlin exception: kotlin.IllegalStateException: Cart is empty",
            "    at 0   iosApp   0x0000000104f2c3a8   kfun:kotlin.Throwable#<init>(kotlin.String?){} + 92 (/opt/buildAgent/work/kotlin/Throwable.kt:24:37)",
            "    at 3   iosApp   0x0000000104f5a1b0   kfun:com.example.shop.Cart#checkout(){} + 120 (/Users/me/shop/shared/src/commonMain/kotlin/com/example/shop/Cart.kt:42:13)",
            "Caused by: kotlin.Exception: timeout",
            "    at 7   iosApp   0x0000000104f5a1b0   kfun:com.example.shop.Api#load(){} + 1 (/Users/me/shop/shared/src/commonMain/kotlin/com/example/shop/Api.kt:9:1)",
            "After the crash",
            "iosApp/ContentView.swift:12: Fatal error: Unexpectedly found nil while unwrapping an Optional value",
            "*** Terminating app due to uncaught exception 'NSInvalidArgumentException', reason: '-[Shop buy]: unrecognized selector'",
        )) { LogParser.console(it) { "t" } }
        assertEquals(listOf("kotlin.IllegalStateException: Cart is empty", "Fatal error: Unexpectedly found nil while unwrapping an Optional value",
            "NSInvalidArgumentException: -[Shop buy]: unrecognized selector"), ios.map { it.title })
        assertEquals(5, ios[0].trace.size)
        assertEquals(listOf("Throwable.kt", "Cart.kt", "Api.kt"), ios[0].trace.mapNotNull { it.frame?.fileName })
        assertEquals(12, ios[1].trace.single().frame?.line)

        val signal = CrashDetector().feed(LogParser.logcat("10-19 12:00:00.000  4321  4321 F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 4321")!!)
        assertEquals("Fatal signal 11 (SIGSEGV)", signal?.title)
    }
}
