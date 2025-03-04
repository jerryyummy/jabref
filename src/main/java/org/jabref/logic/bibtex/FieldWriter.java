package org.jabref.logic.bibtex;

import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.InternalField;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts JabRef's internal BibTeX representation of a BibTeX field to BibTeX text representation
 */
public class FieldWriter {

    // See also ADR-0024
    public static final char BIBTEX_STRING_START_END_SYMBOL = '#';

    private static final Logger LOGGER = LoggerFactory.getLogger(FieldWriter.class);

    private static final char FIELD_START = '{';
    private static final char FIELD_END = '}';

    private final boolean neverFailOnHashes;
    private final FieldPreferences preferences;

    public FieldWriter(FieldPreferences preferences) {
        this(true, preferences);
    }

    private FieldWriter(boolean neverFailOnHashes, FieldPreferences preferences) {
        this.neverFailOnHashes = neverFailOnHashes;
        this.preferences = preferences;
    }

    public static FieldWriter buildIgnoreHashes(FieldPreferences prefs) {
        return new FieldWriter(true, prefs);
    }

    private static void checkBraces(String text) throws InvalidFieldValueException {
        int left = 0;
        int right = 0;

        // First we collect all occurrences:
        for (int i = 0; i < text.length(); i++) {
            char item = text.charAt(i);

            boolean charBeforeIsEscape = false;
            if ((i > 0) && (text.charAt(i - 1) == '\\')) {
                charBeforeIsEscape = true;
            }

            if (!charBeforeIsEscape && (item == '{')) {
                left++;
            } else if (!charBeforeIsEscape && (item == '}')) {
                right++;
            }
        }

        // Then we throw an exception if the error criteria are met.
        if (right != 0 && (left == 0)) {
            LOGGER.error("Unescaped '}' character without opening bracket ends string prematurely. Field value: {}", text);
            throw new InvalidFieldValueException("Unescaped '}' character without opening bracket ends string prematurely. Field value: " + text);
        }
        if (right != 0 && (right < left)) {
            LOGGER.error("Unescaped '}' character without opening bracket ends string prematurely. Field value: {}", text);
            throw new InvalidFieldValueException("Unescaped '}' character without opening bracket ends string prematurely. Field value: " + text);
        }
        if (left != right) {
            LOGGER.error("Braces don't match. Field value: {}", text);
            throw new InvalidFieldValueException("Braces don't match. Field value: " + text);
        }
    }

    /**
     * Formats the content of a field.
     *
     * @param field   the name of the field - used to trigger different serializations, e.g., turning off resolution for some strings
     * @param content the content of the field
     * @return a formatted string suitable for output
     *
     * @throws InvalidFieldValueException if content is not a correct bibtex string, e.g., because of improperly balanced braces or using # not paired
     */
    public String write(Field field, String content) throws InvalidFieldValueException {
        if (content == null) {
            return FIELD_START + "" + FIELD_END;
        }

        if (!shouldResolveStrings(field) || field.equals(InternalField.BIBTEX_STRING)) {
            return formatWithoutResolvingStrings(content);
        }

        return formatAndResolveStrings(content);
    }

    /**
     * This method handles # in the field content to get valid bibtex strings
     * <p>
     * For instance, <code>#jan# - #feb#</code> gets  <code>jan #{ - } # feb</code> (see @link{org.jabref.logic.bibtex.LatexFieldFormatterTests#makeHashEnclosedWordsRealStringsInMonthField()})
     */
    private String formatAndResolveStrings(String content) throws InvalidFieldValueException {
        checkBraces(content);

        content = content.replace("##", "");

        StringBuilder stringBuilder = new StringBuilder();

        // Here we assume that the user encloses any bibtex strings in #, e.g.:
        // #jan# - #feb#
        // ...which will be written to the file like this:
        // jan # { - } # feb
        int pivot = 0;
        while (pivot < content.length()) {
            int pos1 = getFirstOccurrenceOfStartEndSymbol(content, pivot);
            int pos2;
            if (pos1 == -1) {
                // Process content and end the loop after that
                pos1 = content.length();
                pos2 = -1;
            } else {
                pos2 = content.indexOf(BIBTEX_STRING_START_END_SYMBOL, pos1 + 1);
            }

            if (pos2 == -1) {
                if (neverFailOnHashes) {
                    pos1 = content.length(); // just write out the rest of the text, and throw no exception
                } else {
                    LOGGER.error("The character {} is not allowed in BibTeX strings unless escaped as in '\\\\{}'. "
                                    + "In JabRef, use pairs of # characters to indicate a string. "
                                    + "Note that the entry causing the problem has been selected. Field value: {}",
                            BIBTEX_STRING_START_END_SYMBOL,
                            BIBTEX_STRING_START_END_SYMBOL,
                            content);
                    throw new InvalidFieldValueException(
                            "The character " + BIBTEX_STRING_START_END_SYMBOL + " is not allowed in BibTeX strings unless escaped as in '\\" + BIBTEX_STRING_START_END_SYMBOL + "'.\n"
                                    + "In JabRef, use pairs of # characters to indicate a string.\n"
                                    + "Note that the entry causing the problem has been selected. Field value: " + content);
                }
            }

            if (pos1 > pivot) {
                writeText(stringBuilder, content, pivot, pos1);
            }
            if ((pos1 < content.length()) && ((pos2 - 1) > pos1)) {
                // We check that the string label is not empty. That means
                // an occurrence of ## will simply be ignored. Should it instead
                // cause an error message?
                writeStringLabel(stringBuilder, content, pos1 + 1, pos2, pos1 == pivot,
                        (pos2 + 1) == content.length());
            }

            if (pos2 > -1) {
                pivot = pos2 + 1;
            } else {
                pivot = pos1 + 1;
            }
        }

        return stringBuilder.toString();
    }

    /**
     * Finds the first occurrence of # from the pivot point
     */
    private static int getFirstOccurrenceOfStartEndSymbol(String content, int pivot) {
        int goFrom = pivot;
        int pos1 = pivot;
        while (goFrom == pos1) {
            pos1 = content.indexOf(BIBTEX_STRING_START_END_SYMBOL, goFrom);
            if ((pos1 > 0) && (content.charAt(pos1 - 1) == '\\')) {
                pos1++;
                goFrom = pos1;
            } else {
                break;
            }
        }
        return pos1;
    }

    private boolean shouldResolveStrings(Field field) {
        if (preferences.shouldResolveStrings()) {
            // Resolve strings for the list of fields only
            return preferences.getResolvableFields().contains(field);
        }
        return false;
    }

    private String formatWithoutResolvingStrings(String content) throws InvalidFieldValueException {
        checkBraces(content);
        return FIELD_START + content + FIELD_END;
    }

    /**
     * @param stringBuilder the StringBuilder to append the text to
     * @param text          the text to append
     */
    private void writeText(StringBuilder stringBuilder, String text, int startPos, int endPos) {
        stringBuilder.append(FIELD_START);
        stringBuilder.append(text, startPos, endPos);
        stringBuilder.append(FIELD_END);
    }

    /**
     * @param stringBuilder the StringBuilder to append the text to
     * @param text          the text use as basis to get the text to append
     * @param startPos      the position in text where the text to add starts
     * @param endPos        the position in text where the text to add ends
     * @param isFirst       true if the label to write is the first one to write
     * @param isLast        true if the label to write is the last one to write
     */
    private void writeStringLabel(StringBuilder stringBuilder, String text, int startPos, int endPos, boolean isFirst, boolean isLast) {
        String line = (isFirst ? "" : " # ") + text.substring(startPos, endPos) + (isLast ? "" : " # ");
        stringBuilder.append(line);
    }
}
