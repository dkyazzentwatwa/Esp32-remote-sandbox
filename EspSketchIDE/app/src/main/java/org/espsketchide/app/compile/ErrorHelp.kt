package org.espsketchide.app.compile

/**
 * Beginner-friendly explanations of common gcc messages, shown under the raw message in the
 * editor's output panel. Patterns are tried in order; the first match wins, so put specific
 * ones (with a suggestion or an include hint) before general ones.
 */
object ErrorHelp {

    private class Rule(pattern: String, val help: (MatchResult) -> String) {
        val regex = Regex(pattern)
    }

    private val rules = listOf(
        Rule("""^'(.+?)' was not declared in this scope; '.+?' is defined in header '<(.+?)>'""") {
            "${it.name(1)} comes from a library header that isn't included. Add #include <${it.groupValues[2]}> at the top of the sketch."
        },
        Rule("""^'(.+?)' was not declared in this scope; did you mean '(.+?)'\?""") {
            "${it.name(1)} isn't defined anywhere. Did you mean ${it.name(2)}? Check the spelling: C++ cares about capital letters."
        },
        Rule("""^'(.+?)' was not declared in this scope""") {
            "${it.name(1)} isn't defined. Check the spelling and capital letters, declare the variable before you use it " +
                "(for example: int ${it.groupValues[1]} = 0;), or #include the library it comes from."
        },
        Rule("""^'(.+?)' does not name a type; did you mean '(.+?)'\?""") {
            "${it.name(1)} isn't a type C++ knows. Did you mean ${it.name(2)}?"
        },
        Rule("""^'(.+?)' does not name a type""") {
            "${it.name(1)} isn't a type C++ knows. Check the spelling, #include the library that defines it, " +
                "or move the code into a function: outside setup() and loop() you can only declare things."
        },
        Rule("""^(.+?): No such file or directory""") {
            "The file ${it.groupValues[1]} can't be found. If it's a library header, that library isn't installed: " +
                "only the libraries that come with the board pack can be used for now. Also check the spelling and capitals."
        },
        Rule("""^expected ';' before '\}' token""") {
            "A semicolon ; is missing at the end of the last statement before the closing }."
        },
        Rule("""^expected ';'""") {
            "A semicolon ; is missing. Every statement ends with ; and the missing one is usually at the end of the line above."
        },
        Rule("""^expected '\}' at end of input""") {
            "A closing brace } is missing. Every { needs a matching }: count them in setup(), loop() and any if or for blocks."
        },
        Rule("""^expected declaration before '\}' token""") {
            "There's an extra closing brace }. Remove it, or check that each { has exactly one matching }."
        },
        Rule("""^expected '\)'""") {
            "A closing parenthesis ) is missing. Every ( needs a matching )."
        },
        Rule("""^expected primary-expression before '(.+?)'""") {
            "Something is missing before ${it.name(1)}: often a value, a variable name or an extra symbol was left out or typed by mistake."
        },
        Rule("""^stray '\\\d+' in program""") {
            "There's a character C++ doesn't understand, usually a curly quote (“ ” ‘ ’) copied from a website or document. " +
                "Retype the quotes as straight \" and ' characters."
        },
        Rule("""^missing terminating (["']) character""") {
            "A text in quotes isn't closed. Add the closing ${it.groupValues[1]} on the same line."
        },
        Rule("""^redefinition of '(.+?)'""") {
            "${it.name(1)} is defined twice. A sketch can have only one of each function and variable, " +
                "including only one setup() and one loop(), even across tabs."
        },
        Rule("""^too (few|many) arguments to function '(.+?)'""") {
            val which = if (it.groupValues[1] == "few") "too few" else "too many"
            "The call passes $which values. The function expects: ${it.groupValues[2]}"
        },
        Rule("""^lvalue required as left operand of assignment""") {
            "The left side of = can't be changed. If you meant to compare two values (for example in an if), use == instead of =."
        },
        Rule("""^suggest parentheses around assignment used as truth value""") {
            "This if (or while) uses = which sets a value. To compare two values, use ==."
        },
        Rule("""^invalid conversion from '(.+?)' to '(.+?)'""") {
            "A value of type ${it.name(1)} is used where ${it.name(2)} is expected. Check that you're passing the right kind of value."
        },
    )

    /** A plain-language explanation of [message], or null if it isn't one we recognise. */
    fun explain(message: String): String? =
        rules.firstNotNullOfOrNull { rule -> rule.regex.find(message)?.let(rule.help) }

    private fun MatchResult.name(group: Int) = "'${groupValues[group]}'"
}
