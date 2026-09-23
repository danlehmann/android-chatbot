package net.daniellehmann.localchat.ui

/*
 * Best-effort conversion of common LaTeX math to plain Unicode so formulas
 * read naturally in a text bubble without a layout engine. Covers Greek
 * letters, operators, \frac, \sqrt, super/subscripts, and strips the
 * spacing/formatting commands that would otherwise show up as noise.
 */

private val greek = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
    "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ", "sigma" to "σ",
    "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
    "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
)

private val symbols = mapOf(
    "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "le" to "≤", "leq" to "≤",
    "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠", "approx" to "≈", "equiv" to "≡", "sim" to "∼",
    "propto" to "∝", "infty" to "∞", "partial" to "∂", "nabla" to "∇", "sum" to "∑", "prod" to "∏",
    "int" to "∫", "oint" to "∮", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
    "Leftarrow" to "⇐", "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "mapsto" to "↦", "in" to "∈",
    "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "cup" to "∪", "cap" to "∩",
    "emptyset" to "∅", "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨",
    "wedge" to "∧", "vee" to "∨", "oplus" to "⊕", "otimes" to "⊗", "circ" to "∘", "bullet" to "•",
    "ldots" to "…", "cdots" to "⋯", "dots" to "…", "vdots" to "⋮", "ddots" to "⋱", "prime" to "′",
    "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "star" to "⋆", "ast" to "∗", "hbar" to "ℏ",
    "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ", "degree" to "°", "langle" to "⟨", "rangle" to "⟩",
    "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉", "quad" to "  ", "qquad" to "    ",
    "," to " ", ";" to " ", "!" to "", " " to " ", "{" to "{", "}" to "}", "%" to "%", "&" to "&", "_" to "_",
    "#" to "#", "\\" to "\n", "|" to "‖", "vert" to "|", "backslash" to "\\",
)

private val superscripts = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ', 'T' to 'ᵀ', 'a' to 'ᵃ',
    'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'j' to 'ʲ', 'k' to 'ᵏ',
    'l' to 'ˡ', 'm' to 'ᵐ', 'o' to 'ᵒ', 'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ', 'v' to 'ᵛ',
    'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ', '*' to '*', '′' to '′',
)

private val subscripts = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎', 'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ',
    'j' to 'ⱼ', 'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ',
    't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ',
)

/** Commands whose only argument should be shown unchanged (formatting wrappers). */
private val transparent = setOf(
    "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "mathcal", "mathbb", "text", "textbf", "textit",
    "operatorname", "boldsymbol", "bm", "vec", "hat", "bar", "tilde", "dot", "ddot", "overline", "underline",
    "left", "right", "displaystyle", "textstyle", "mathrm", "mbox", "label", "tag", "nonumber",
)

fun latexToText(src: String): String {
    val out = StringBuilder()
    var i = 0
    val s = src
    fun readGroup(): String {
        // Reads either a {...} group or a single token (char or \command) starting at i.
        while (i < s.length && s[i] == ' ') i++
        if (i >= s.length) return ""
        if (s[i] == '{') {
            var depth = 0
            val start = i + 1
            while (i < s.length) {
                if (s[i] == '{') depth++ else if (s[i] == '}') { depth--; if (depth == 0) break }
                i++
            }
            val inner = s.substring(start, minOf(i, s.length))
            i++ // past }
            return latexToText(inner)
        }
        if (s[i] == '\\') {
            val start = i
            i++
            while (i < s.length && s[i].isLetter()) i++
            if (i == start + 1 && i < s.length) i++ // single-char command like \,
            return latexToText(s.substring(start, i))
        }
        return s[i++].toString()
    }
    fun script(text: String, map: Map<Char, Char>, fallback: Char): String {
        val mapped = text.map { map[it] }
        return if (mapped.all { it != null }) mapped.joinToString("") { it.toString() }
        else "$fallback(" + text + ")"
    }
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' -> {
                val start = i + 1
                i++
                while (i < s.length && s[i].isLetter()) i++
                var name = s.substring(start, i)
                if (name.isEmpty() && i < s.length) { name = s[i].toString(); i++ }
                when {
                    name == "frac" || name == "dfrac" || name == "tfrac" -> {
                        val a = readGroup(); val b = readGroup()
                        out.append(wrap(a)).append('/').append(wrap(b))
                    }
                    name == "sqrt" -> {
                        if (i < s.length && s[i] == '[') { val e = s.indexOf(']', i); val n = s.substring(i + 1, e); i = e + 1; out.append(script(latexToText(n), superscripts, '^')) }
                        out.append('√').append(wrap(readGroup(), always = true))
                    }
                    name in transparent -> {
                        if (name == "left" || name == "right") { /* delimiter follows, keep it */ }
                        else out.append(readGroup())
                    }
                    name == "begin" || name == "end" -> { readGroup() }
                    greek.containsKey(name) -> out.append(greek[name])
                    symbols.containsKey(name) -> out.append(symbols[name])
                    else -> out.append(name) // unknown command: show its name, e.g. sin, log, lim
                }
            }
            c == '^' -> { i++; out.append(script(readGroup(), superscripts, '^')) }
            c == '_' -> { i++; out.append(script(readGroup(), subscripts, '_')) }
            c == '{' || c == '}' -> i++
            c == '~' -> { out.append(' '); i++ }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString().replace(Regex(" {2,}"), " ").trim()
}

private fun wrap(t: String, always: Boolean = false): String =
    if (!always && (t.length == 1 || t.all { it.isLetterOrDigit() })) t else "($t)"
