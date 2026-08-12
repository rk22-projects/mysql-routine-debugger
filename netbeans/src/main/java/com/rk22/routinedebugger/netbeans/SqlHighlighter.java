package com.rk22.routinedebugger.netbeans;

import javax.swing.text.*;
import java.awt.Color;
import java.util.Set;

/**
 * Applies basic SQL syntax highlighting to a DefaultStyledDocument.
 * Mirrors the JS highlightLines() function from the web UI.
 */
public class SqlHighlighter {

    private static final Set<String> KEYWORDS = Set.of(
        "SELECT","FROM","WHERE","INSERT","INTO","UPDATE","DELETE","SET","BEGIN","END",
        "IF","THEN","ELSE","ELSEIF","WHILE","DO","LOOP","REPEAT","UNTIL","LEAVE",
        "ITERATE","RETURN","DECLARE","CALL","AND","OR","NOT","NULL","TRUE","FALSE",
        "CASE","WHEN","PROCEDURE","FUNCTION","RETURNS","DETERMINISTIC","HANDLER",
        "CURSOR","FOR","EACH","ROW","CREATE","DROP","ALTER","TABLE","INDEX","PRIMARY",
        "KEY","REFERENCES","DEFAULT","CONSTRAINT","JOIN","LEFT","RIGHT","INNER",
        "OUTER","CROSS","ON","AS","DISTINCT","ALL","UNION","HAVING","GROUP","ORDER",
        "BY","LIMIT","OFFSET","EXISTS","BETWEEN","LIKE","IS","OPEN","CLOSE","FETCH",
        "FOUND","SIGNAL","RESIGNAL","SQLSTATE","SQLEXCEPTION","SQLWARNING","CONTINUE",
        "EXIT","UNDO","MODIFIES","READS","SQL","DATA","CONTAINS","NO","LANGUAGE",
        "SECURITY","DEFINER","INVOKER","REPLACE","VALUES","IN","OUT","INOUT",
        "INT","VARCHAR","TEXT","DATETIME","DATE","TIME","BOOLEAN","BOOL","CHAR",
        "DECIMAL","FLOAT","DOUBLE","BIGINT","TINYINT","SMALLINT","MEDIUMINT",
        "LONGTEXT","MEDIUMTEXT","BINARY","BLOB","TRIGGER","VIEW"
    );

    private static final Set<String> BUILTINS = Set.of(
        "NOW","COUNT","SUM","AVG","MAX","MIN","COALESCE","NULLIF","IFNULL","CAST",
        "CONVERT","CONCAT","LENGTH","SUBSTRING","SUBSTR","UPPER","LOWER","TRIM",
        "REPLACE","ROUND","FLOOR","CEIL","ABS","MOD","SLEEP","LAST_INSERT_ID",
        "ROW_COUNT","FOUND_ROWS","UUID","MD5","SHA1","SHA2","STR_TO_DATE",
        "DATE_FORMAT","DATEDIFF","TIMESTAMPDIFF","YEAR","MONTH","DAY","HOUR",
        "MINUTE","SECOND","CURDATE","CURTIME","SYSDATE","ISNULL","GREATEST","LEAST"
    );

    // Style colours
    private static final Color C_DEFAULT = new Color(0x1E, 0x1E, 0x1E);
    private static final Color C_KEYWORD = new Color(0x00, 0x00, 0xFF);
    private static final Color C_BUILTIN = new Color(0x79, 0x5E, 0x26);
    private static final Color C_STRING  = new Color(0xA3, 0x15, 0x15);
    private static final Color C_COMMENT = new Color(0x00, 0x80, 0x00);
    private static final Color C_NUMBER  = new Color(0x09, 0x86, 0x58);
    private static final Color C_IDENT   = new Color(0x26, 0x7F, 0x99);
    private static final Color C_VAR     = new Color(0x00, 0x70, 0xC1);

    private final SimpleAttributeSet aDefault = style(C_DEFAULT, false);
    private final SimpleAttributeSet aKeyword = style(C_KEYWORD, true);
    private final SimpleAttributeSet aBuiltin = style(C_BUILTIN, false);
    private final SimpleAttributeSet aString  = style(C_STRING, false);
    private final SimpleAttributeSet aComment = style(C_COMMENT, false);
    private final SimpleAttributeSet aNumber  = style(C_NUMBER, false);
    private final SimpleAttributeSet aIdent   = style(C_IDENT, false);
    private final SimpleAttributeSet aVar     = style(C_VAR, false);

    private static SimpleAttributeSet style(Color c, boolean bold) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, c);
        StyleConstants.setBold(a, bold);
        return a;
    }

    /** Apply highlighting to the entire document (call after setText). */
    public void highlight(DefaultStyledDocument doc, String text) {
        try {
            doc.setCharacterAttributes(0, text.length(), aDefault, true);
            applyHighlighting(doc, text);
        } catch (Exception ignored) {}
    }

    private void applyHighlighting(DefaultStyledDocument doc, String text) throws BadLocationException {
        int len      = text.length();
        boolean inBlock = false;

        for (int i = 0; i < len; ) {
            char c = text.charAt(i);

            if (inBlock) {
                int end = text.indexOf("*/", i);
                if (end < 0) { doc.setCharacterAttributes(i, len - i, aComment, true); break; }
                doc.setCharacterAttributes(i, end + 2 - i, aComment, true);
                i = end + 2; inBlock = false; continue;
            }

            // line comment
            if ((c == '-' && i + 1 < len && text.charAt(i + 1) == '-' &&
                 (i + 2 == len || Character.isWhitespace(text.charAt(i + 2)))) || c == '#') {
                int eol = text.indexOf('\n', i);
                int end = eol < 0 ? len : eol;
                doc.setCharacterAttributes(i, end - i, aComment, true);
                i = end; continue;
            }

            // block comment
            if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                if (end < 0) { doc.setCharacterAttributes(i, len - i, aComment, true); inBlock = true; break; }
                doc.setCharacterAttributes(i, end + 2 - i, aComment, true);
                i = end + 2; continue;
            }

            // string literal
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < len) {
                    if (text.charAt(j) == '\\') { j += 2; continue; }
                    if (text.charAt(j) == c) {
                        if (j + 1 < len && text.charAt(j + 1) == c) { j += 2; continue; }
                        j++; break;
                    }
                    j++;
                }
                doc.setCharacterAttributes(i, j - i, aString, true);
                i = j; continue;
            }

            // backtick identifier
            if (c == '`') {
                int j = i + 1;
                while (j < len) {
                    if (text.charAt(j) == '`' && j + 1 < len && text.charAt(j + 1) == '`') { j += 2; continue; }
                    if (text.charAt(j) == '`') { j++; break; }
                    j++;
                }
                doc.setCharacterAttributes(i, Math.min(j, len) - i, aIdent, true);
                i = j; continue;
            }

            // MySQL user and system variables
            if (c == '@') {
                int j = i + 1;
                if (j < len && text.charAt(j) == '@') j++;
                while (j < len && (Character.isLetterOrDigit(text.charAt(j)) || "_$".indexOf(text.charAt(j)) >= 0)) j++;
                doc.setCharacterAttributes(i, j - i, aVar, true);
                i = j; continue;
            }

            // number
            if (Character.isDigit(c) && (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))) {
                int j = i;
                while (j < len && "0123456789.eExXbBabcdefABCDEF".indexOf(text.charAt(j)) >= 0) j++;
                doc.setCharacterAttributes(i, j - i, aNumber, true);
                i = j; continue;
            }

            // word
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < len && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) j++;
                String word = text.substring(i, j).toUpperCase();
                SimpleAttributeSet attr = KEYWORDS.contains(word) ? aKeyword
                                        : BUILTINS.contains(word) ? aBuiltin
                                        : aDefault;
                doc.setCharacterAttributes(i, j - i, attr, true);
                i = j; continue;
            }

            i++;
        }
    }
}
