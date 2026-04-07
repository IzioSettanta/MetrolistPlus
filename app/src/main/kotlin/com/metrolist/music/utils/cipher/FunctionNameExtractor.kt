package com.metrolist.music.utils.cipher

import timber.log.Timber

object FunctionNameExtractor {
    private const val TAG = "Metrolist_CipherFnExtract"

    // ==================== DATA CLASSES ====================

    data class SigFunctionInfo(
        val name: String,
        val constantArg: Int?, // The first numeric argument (e.g., 48 in JI(48, sig)) - legacy
        val constantArgs: List<Int>? = null, // All constant args e.g., JI(48, 1918, ...) -> [48, 1918]
        val preprocessFunc: String? = null, // Preprocessing function e.g., f1
        val preprocessArgs: List<Int>? = null, // Preprocess args e.g., f1(1, 6528, sig) -> [1, 6528]
        val isHardcoded: Boolean = false
    )

    data class NFunctionInfo(
        val name: String,
        val arrayIndex: Int?, // e.g. FUNC[0] -> index=0
        val constantArgs: List<Int>? = null, // e.g. GU(6, 6010, n) -> [6, 6010]
        val isHardcoded: Boolean = false
    )

    /**
     * Hardcoded player.js configuration for when regex extraction fails
     * Due to Q-array obfuscation, patterns like `.get("n")` become `Q[T^6001]`
     */
    data class HardcodedPlayerConfig(
        val sigFuncName: String,
        val sigConstantArg: Int?, // Legacy single arg
        val sigConstantArgs: List<Int>? = null, // e.g. JI(48, 1918, ...) -> [48, 1918]
        val sigPreprocessFunc: String? = null, // e.g. f1
        val sigPreprocessArgs: List<Int>? = null, // e.g. f1(1, 6528, sig) -> [1, 6528]
        val nFuncName: String,
        val nArrayIndex: Int?,
        val nConstantArgs: List<Int>?, // e.g. GU(6, 6010, n) -> [6, 6010]
        val signatureTimestamp: Int
    )

    // ==================== KNOWN PLAYER CONFIGS ====================

    /**
     * Known player.js configurations indexed by hash
     *
     * Player hash 74edf1a3 (March 2026):
     * - Signature: JI(48, 1918, f1(1, 6528, sig)) -> reverse, swap(0, 57%), reverse
     * - N-transform: GU(6, 6010, n) with 87-element self-referential array
     */
    private val KNOWN_PLAYER_CONFIGS = mapOf(
        "74edf1a3" to HardcodedPlayerConfig(
            sigFuncName = "JI",
            sigConstantArg = 48, // Legacy
            sigConstantArgs = listOf(48, 1918), // JI(48, 1918, processedSig)
            sigPreprocessFunc = "f1", // sig must be preprocessed through f1()
            sigPreprocessArgs = listOf(1, 6528), // f1(1, 6528, sig)
            nFuncName = "GU",
            nArrayIndex = null, // Direct function, not array access
            nConstantArgs = listOf(6, 6010), // GU(6, 6010, n) - the function requires 3 args!
            signatureTimestamp = 20522
        ),
        "f4c47414" to HardcodedPlayerConfig(
            sigFuncName = "hJ",
            sigConstantArg = 6,
            sigConstantArgs = listOf(6), // hJ(6, decodeURIComponent(h.s))
            sigPreprocessFunc = null, // No preprocessing needed
            sigPreprocessArgs = null,
            nFuncName = "", // Will be extracted via regex
            nArrayIndex = null,
            nConstantArgs = null,
            signatureTimestamp = 20543
        )
    )

    // ==================== DETECTION PATTERNS ====================

    // Detect Q-array obfuscation: var Q="...".split("}")
    private val Q_ARRAY_PATTERN = Regex("""var\s+Q\s*=\s*"[^"]+"\s*\.\s*split\s*\(\s*"\}"\s*\)""")

    // Extract player hash from common patterns
    private val PLAYER_HASH_PATTERNS = listOf(
        Regex("""jsUrl['":\s]+[^"']*?/player/([a-f0-9]{8})/"""),
        Regex("""player_ias\.vflset/[^/]+/([a-f0-9]{8})/"""),
        Regex("""/s/player/([a-f0-9]{8})/""")
    )

    // Modern 2025+ signature deobfuscation function patterns
    // The sig function is called as: FUNC(NUMBER, decodeURIComponent(encryptedSig))
    // within a logical expression: VAR && (VAR = FUNC(NUM, decodeURIComponent(VAR)), ...)
    private val SIG_FUNCTION_PATTERNS = listOf(
        // Pattern 1 (2025+): &&(VAR=FUNC(NUM,decodeURIComponent(VAR))
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\)"""),
        // Pattern 1a (April 2026): &&(z=hJ(6,decodeURIComponent(h.s))
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\.\s*[a-z]\s*\)"""),
        // Classic patterns (pre-2025, kept as fallback)
        Regex("""\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("""\b[a-zA-Z0-9]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("""\bm=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\(h\.s\)\)"""),
        Regex("""\bc\s*&&\s*d\.set\([^,]+\s*,\s*(?:encodeURIComponent\s*\()([a-zA-Z0-9$]+)\("""),
        Regex("""\bc\s*&&\s*[a-z]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
    )

    // N-parameter (throttle) transform function patterns
    // The n-function transforms the 'n' parameter in streaming URLs to avoid throttling/403
    // Pattern: .get("n"))&&(b=FUNC[INDEX](a[0]))  or  .get("n"))&&(b=FUNC(a[0]))
    private val N_FUNCTION_PATTERNS = listOf(
        // Pattern 1: .get("n"))&&(b=FUNC[IDX](VAR)
        Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(([a-zA-Z0-9])\)"""),
        // Pattern 2: .get("n"))&&(FUNC=VAR[IDX](FUNC)  (2025+ variant)
        Regex("""\.get\("n"\)\)\s*&&\s*\(([a-zA-Z0-9$]+)\s*=\s*([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(\1\)"""),
        // Pattern 3: .get("n");if(m){var M=n.match... (April 2026 variant)
        Regex("""\.get\("n"\);if\([a-zA-Z0-9$]+\)\s*\{[^}]*match"""),
        // Pattern 4: String.fromCharCode(110) variant (110 = 'n')
        Regex("""\(\s*([a-zA-Z0-9$]+)\s*=\s*String\.fromCharCode\(110\)"""),
        // Pattern 5: enhanced_except_ function pattern
        Regex("""([a-zA-Z0-9$]+)\s*=\s*function\([a-zA-Z0-9]\)\s*\{[^}]*?enhanced_except_"""),
    )

    fun extractSigFunctionInfo(playerJs: String): SigFunctionInfo? {
        for ((index, pattern) in SIG_FUNCTION_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                val name = match.groupValues[1]
                val constArg = if (match.groupValues.size > 2) match.groupValues[2].toIntOrNull() else null
                Timber.tag(TAG).d("Sig function found with pattern $index: $name (constantArg=$constArg)")
                return SigFunctionInfo(
                    name = name,
                    constantArg = constArg,
                    constantArgs = if (constArg != null) listOf(constArg) else null,
                    preprocessFunc = null,
                    preprocessArgs = null,
                    isHardcoded = false
                )
            }
        }
        Timber.tag(TAG).e("Could not find signature deobfuscation function name")
        return null
    }

    fun extractNFunctionInfo(playerJs: String): NFunctionInfo? {
        for ((index, pattern) in N_FUNCTION_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                when (index) {
                    0 -> {
                        // Pattern 1: group1=funcName, group2=arrayIndex (optional)
                        val name = match.groupValues[1]
                        val arrayIdx = match.groupValues[2].toIntOrNull()
                        Timber.tag(TAG).d("N-function found with pattern $index: $name (arrayIndex=$arrayIdx)")
                        return NFunctionInfo(
                            name = name,
                            arrayIndex = arrayIdx,
                            constantArgs = null,
                            isHardcoded = false
                        )
                    }
                    1 -> {
                        // Pattern 2: group2=funcName, group3=arrayIndex (optional)
                        val name = match.groupValues[2]
                        val arrayIdx = match.groupValues[3].toIntOrNull()
                        Timber.tag(TAG).d("N-function found with pattern $index: $name (arrayIndex=$arrayIdx)")
                        return NFunctionInfo(
                            name = name,
                            arrayIndex = arrayIdx,
                            constantArgs = null,
                            isHardcoded = false
                        )
                    }
                    else -> {
                        val name = match.groupValues[1]
                        Timber.tag(TAG).d("N-function found with pattern $index: $name")
                        return NFunctionInfo(
                            name = name,
                            arrayIndex = null,
                            constantArgs = null,
                            isHardcoded = false
                        )
                    }
                }
            }
        }
        Timber.tag(TAG).e("Could not find n-transform function name")
        return null
    }
}
