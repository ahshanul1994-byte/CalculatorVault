package com.example.calculatorvault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.floor

/** Plain calculator logic (iOS-style, left-to-right). Also tells the host when 6 fresh digits were typed. */
internal class Calc {
    var display by mutableStateOf("0")
        private set

    private var input = ""
    private var first: Double? = null
    private var op: String? = null
    private var waiting = false
    private var justEval = false

    fun reset() {
        input = ""; display = "0"; first = null; op = null; waiting = false; justEval = false
    }

    /** Exactly 6 freshly typed digits (not a calculation result) → possible PIN. */
    fun pinCandidate(): String? =
        if (!justEval && input.length == 6 && input.all { it.isDigit() }) input else null

    fun hasPendingOp(): Boolean = op != null

    /** After a failed PIN guess: behave like a normal "=" so nothing looks unusual. */
    fun markEvaluated() { justEval = true }

    private fun setInput(s: String) { input = s; display = s.ifEmpty { "0" } }

    fun press(key: String) {
        when (key) {
            "C" -> reset()
            "⌫" -> {
                if (!justEval && input.isNotEmpty()) setInput(input.dropLast(1))
                else { setInput(""); justEval = false }
            }
            "+/−" -> {
                input.toDoubleOrNull()?.let { setInput(fmt(-it)) }
                justEval = false
            }
            "%" -> {
                input.toDoubleOrNull()?.let { setInput(fmt(it / 100.0)) }
                justEval = false
            }
            "÷", "×", "−", "+" -> {
                val v = input.toDoubleOrNull() ?: display.toDoubleOrNull() ?: 0.0
                val f = first
                val o = op
                if (f != null && !waiting && o != null) {
                    val r = calc(f, o, v)
                    first = r; display = fmt(r)
                } else {
                    first = v
                }
                op = key; waiting = true; input = ""; justEval = false
            }
            "." -> {
                if (justEval || waiting) { setInput("0."); justEval = false; waiting = false }
                else if (!input.contains('.')) setInput(if (input.isEmpty()) "0." else "$input.")
            }
            else -> if (key.length == 1 && key[0].isDigit()) {
                val s = when {
                    justEval || waiting -> key
                    input == "0" -> key
                    input.length >= 12 -> return
                    else -> input + key
                }
                waiting = false; justEval = false
                setInput(s)
            }
        }
    }

    fun equalsKey() {
        val f = first
        val o = op
        if (f != null && o != null) {
            val v = input.toDoubleOrNull() ?: f
            val s = fmt(calc(f, o, v))
            display = s
            input = if (s == "Error") "" else s
            first = null; op = null; waiting = false; justEval = true
        }
    }

    private fun calc(a: Double, op: String, b: Double): Double = when (op) {
        "+" -> a + b
        "−" -> a - b
        "×" -> a * b
        "÷" -> if (b != 0.0) a / b else Double.NaN
        else -> b
    }

    private fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "Error"
        if (v == floor(v) && abs(v) < 1e12) return v.toLong().toString()
        if (abs(v) >= 1e12 || abs(v) < 1e-6) return "%.6g".format(v)
        return BigDecimal(v).round(MathContext(10)).stripTrailingZeros().toPlainString()
    }
}
