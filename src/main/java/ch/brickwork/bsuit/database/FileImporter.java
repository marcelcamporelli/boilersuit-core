package ch.brickwork.bsuit.database;

import ch.brickwork.bsuit.globals.IBoilersuitApplicationContext;
import ch.brickwork.bsuit.util.FileIOUtils;
import org.apache.commons.io.ByteOrderMark;
import org.apache.commons.io.input.BOMInputStream;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Provides an iterator to parse text lines of a CSV file into Records. Also contains all necessary helping methods
 * (analyse delimiter, etc.).<br/><br/>
 * Delimiters are auto-recognized and can be ',' or ';'. Values can be encapsulated in what we call "brackets". This
 * can be either " or '. This is also auto-recognized.
 */
public class FileImporter implements Iterable<Record> {

    private static final Logger LOG = Logger.getLogger(FileImporter.class.getCanonicalName());

    /**
     * the following quotes would not finish
     */
    private static final String[] DEFAULT_QUOTE_LITERALS_REGEXP = {"\"\"",   // double quote "" regexp
            "\\\\\""  // escaped quote \" regexp
    };


    /**
     * the above quote literals actually mean the following values (in same order):
     */
    private static final String[] DEFAULT_QUOTE_LITERAL_TRANSLATION = {"\"", "\""};

    private static final String LINE_BREAK_LITERAL = "<linebreak>";

    private static final int MAX_WARN_COUNT = 100;

    private final static String LOG_FILE_NAME = "boilersuit_import_export.log";

    private String logFilePath;

    private static final String DEFAULT_ENCODING = "UTF-8";

    private final File file;

    private String encoding;

    private String bracket = null;

    private String[] columnNames;

    private String commaDelimitator = null;

    private boolean firstLine;

    private BufferedReader reader;

    private int rowCount = 0;

    private IBoilersuitApplicationContext context;

    private int warnCount = 0;

    /**
     * Current Assumption: 1. always with header; 2. only with valid header; 3.
     * comma-sep ',' or ';' names (that can be used as attribute names in the database)
     *
     * @param file - file without path
     * @param encoding
     */
    public FileImporter(File file, String encoding, IBoilersuitApplicationContext context)
    {
        this.file = file;
        this.encoding = encoding;
        this.context = context;
        init();
    }


    public String[] getColumnNames()
    {
        return columnNames;
    }

    /**
     * Implementing the {@link Iterator} interface allows an object to be the target of
     * the "foreach" statement.
     */
    public Iterator<Record> iterator()
    {
        return new Iterator<Record>() {
            @Override
            public boolean hasNext()
            {
                try {
                    return reader.ready();
                } catch (IOException e) {
                    context.getLog().err("IO Problem: " + e.getMessage() + e.getStackTrace());
                    e.printStackTrace();
                    return false;
                }
            }

            @Override
            public Record next() {
                final Record record = new Record();
                try {
                    boolean gotValidLine = false;
                    boolean lineNotCompleteBecauseOfLineBreaks = false;
                    List<String> rawValues = null;

                    String line;
                    String completeLine = "";
                    while (!gotValidLine && (line = reader.readLine()) != null) {
                        completeLine += line.replaceAll("\n", "");
                        rawValues = Arrays.asList(splitLine(completeLine));

                        // more than 0 values?
                        if (rawValues != null) {
                            if (rawValues.size() > 0) {

                                // line break?
                                if(bracket != null && rawValues.get(rawValues.size() - 1).endsWith("\n")) {
                                    lineNotCompleteBecauseOfLineBreaks = true;

                                    // simply read next line, maybe we are getting complete at some point
                                    rawValues.set(rawValues.size() - 1, rawValues.get(rawValues.size() - 1).replaceAll("\n", LINE_BREAK_LITERAL));

                                    warn("Check Line " + rowCount + "; there were line breaks in a value (within " + bracket + " brackets), so we took multiple lines together to form one record.");
                                }

                                else {
                                    // incomplete or overloaded?
                                    if (rawValues.size() != columnNames.length) {
                                        // empty?
                                        if (rawValues.size() == 1 && rawValues.get(0) != null && rawValues.get(0).trim().equals("")) {
                                            warn("Ignored empty line at " + rowCount);
                                            continue;
                                        } else if (rawValues.size() > columnNames.length) {
                                            warn("Check Line " + rowCount + "; there were too many values on that line: " + rawValues.size() + "/" + columnNames.length);
                                        } else {
                                            if (!lineNotCompleteBecauseOfLineBreaks) {
                                                warn("Check Line " + rowCount + "; not same number of values as in header. Check log file. [" + rawValues.size() + "/" + columnNames.length + "]");
                                            }
                                            appendToLogFile(rawValues.size() + "/" + columnNames.length + " values only (row " + rowCount + "): " + line);
                                        }
                                    }


                                    // ok, then that's valid
                                    gotValidLine = true;
                                    completeLine = "";

                                    for (int i = 0; i < columnNames.length; i++) {
                                        if (i < rawValues.size()) {
                                            record.put(columnNames[i], unencloseText(rawValues.get(i)));
                                        }

                                        // if less raw values than columns (as per header), just fill up with empty
                                        // cells at the right side:
                                        else {
                                            record.put(columnNames[i], "");
                                        }
                                    }
                                }
                            }
                        }
                    }

                    rowCount++;
                } catch (IOException e) {
                    context.getLog().err("IO Problem in next" + e.getMessage() + e.getStackTrace());
                    e.printStackTrace();
                }
                return record;
            }

            @Override
            public void remove() {
            }
        };

    }

    private void warn(String s) {
        if(warnCount < MAX_WARN_COUNT)
            context.getLog().warn(s);
        else if(warnCount == MAX_WARN_COUNT)
            context.getLog().warn("Too many warnings. If you want to see them all, check the import/export logfile in your folder");
        warnCount++;
    }

    private void initLogFile() {
        File f = new File(logFilePath);
        if(f.exists())
            f.delete();
        try {
            f.createNewFile();
        } catch (IOException e) {
            context.getLog().err("IO Problem in init" + e.getMessage() + e.getStackTrace());
            e.printStackTrace();
        }
    }

    private void appendToLogFile(String s) {
        FileIOUtils.overwriteFile(logFilePath, FileIOUtils.readCompleteFile(context.getWorkingDirectory(), logFilePath) + "\n" + s);
    }

    /**
     * @param line of text
     *
     * @return bracket by (naivly) splitting between the default delimiter, and then just taking the very first character
     * of the very first token, and the very last character of the very last token. If these are both equal to ", then "
     * is the bracket. If they are both equal to ', then ' is the bracket. Otherwise, null is the bracket.
     */
    private String getBracket(String line)
    {
        final String[] commaSplit = line.split(",");
        if (commaSplit.length > 0 && commaSplit[0].trim().length() > 0 && commaSplit[(commaSplit.length - 1)].trim().length() > 0) {
            final String commaLeftMost = commaSplit[0].trim().substring(0, 1);
            final String commaRightMost = commaSplit[commaSplit.length - 1].trim().substring(commaSplit[commaSplit.length - 1].trim().length() - 1);
            if (commaLeftMost.equals("\"") && commaRightMost.equals("\"")) {
                return "\"";
            }
            if (commaLeftMost.equals("'") && commaLeftMost.equals("'")) {
                return "'";
            }
        }

        final String[] semicolonSplit = line.split(";", -1);
        if (semicolonSplit.length > 0 && semicolonSplit[0].trim().length() > 0 && semicolonSplit[(semicolonSplit.length - 1)].trim().length() > 0) {
            final String semicolonLeftMost = semicolonSplit[0].trim().substring(0, 1);
            final String semicolonRightMost = semicolonSplit[semicolonSplit.length - 1].trim()
                    .substring(semicolonSplit[semicolonSplit.length - 1].trim().length() - 1);
            if (semicolonLeftMost.equals("\"") && semicolonRightMost.equals("\"")) {
                return "\"";
            }
            if (semicolonLeftMost.equals("'") && semicolonLeftMost.equals("'")) {
                return "'";
            }
        }

        return null;
    }

    /**
     * init and reads first line
     */
    private void init()
    {
        logFilePath = context.getWorkingDirectory() + "/" + LOG_FILE_NAME;
        initLogFile();

        firstLine = true;
        rowCount = 0;
        try {
            BOMInputStream bomIn = new BOMInputStream(new FileInputStream(file),
                    ByteOrderMark.UTF_8, ByteOrderMark.UTF_16LE, ByteOrderMark.UTF_16BE,
                    ByteOrderMark.UTF_32LE, ByteOrderMark.UTF_32BE
            );
            if (!bomIn.hasBOM()) {
                context.getLog().info("Using default encoding " + DEFAULT_ENCODING);
                encoding = DEFAULT_ENCODING;
            } else if(bomIn.hasBOM(ByteOrderMark.UTF_8)) {
                context.getLog().info("Detected UTF-8 encoding");
                encoding = "UTF-8";
            } else if (bomIn.hasBOM(ByteOrderMark.UTF_16LE)) {
                context.getLog().info("Detected UTF-16LE encoding");
                encoding = "UTF-16LE";
            } else if (bomIn.hasBOM(ByteOrderMark.UTF_16BE)) {
                context.getLog().info("Detected UTF-16BE encoding");
                encoding = "UTF-16BE";
            } else if (bomIn.hasBOM(ByteOrderMark.UTF_32LE)) {
                context.getLog().info("Detected UTF-32LE encoding");
                encoding = "UTF-32LE";
            } else if (bomIn.hasBOM(ByteOrderMark.UTF_32BE)) {
                context.getLog().info("Detected UTF-32BE encoding");
                encoding = "UTF-32BE";
            }

            reader = new BufferedReader(new InputStreamReader(bomIn, encoding));

            // read first line (header)
            readFirstLine();

            // clean column names
            if (null != columnNames) {
               context.getDatabase().cleanColumnNames(columnNames);
            }
        } catch (FileNotFoundException e) {
            context.getLog().err("File not found" + e.getMessage() + e.getStackTrace());
        } catch (UnsupportedEncodingException e) {
            context.getLog().err("Encoding problem" + e.getMessage() + e.getStackTrace());
        } catch (IOException e) {
            context.getLog().err("IO Problem in init" + e.getMessage() + e.getStackTrace());
        }
    }

    /**
     * Called within readFirstLine to initially set the brackets
     *
     * @param firstLine first found line
     */
    private void initBrackets(String firstLine)
    {
        bracket = getBracket(firstLine);
    }

    /**
     * Called within readFirstLine to initially set the comma delimitator.
     * it is assumed that the delimiter is either ',' or ';', and so we count just their occurrence.
     * If there is more ',' than ';' then ',' is assumed as delimiter, and vice versa. Of course,
     * this only works if there is not too many of the "loser" delimiter within brackets.
     */
    private void initCommaDelimitator(String firstLine)
    {
        final int commaCount = firstLine.length() - firstLine.replace(",", "").length();
        final int semiCount = firstLine.length() - firstLine.replace(";", "").length();
        commaDelimitator = (commaCount > semiCount) ? "," : ";";
    }

    /**
     * read header line and init bracket and delimiter and columnNames. if -1, without limits
     */
    private void readFirstLine()
    {
        try {
            final String line = reader.readLine();
            if (line != null) {

                // determine delimiter and brackets
                initCommaDelimitator(line);
                initBrackets(line);

                // read column names on first line (header)
                if (firstLine) {
                    final String[] rawColumnNames = splitLine(line);
                    if (rawColumnNames != null) {
                        columnNames = new String[rawColumnNames.length];
                        for (int i = 0; i < rawColumnNames.length; i++) {
                            columnNames[i] = unencloseText(rawColumnNames[i].trim());
                        }
                    }
                    firstLine = false;
                }
                rowCount++;
            }
        } catch (IOException e) {
            context.getLog().err("IO Problem in readFirstLine" + e.getMessage() + e.getStackTrace());
        }
    }

    /**
     * splits a line (header or values) into an array of strings. So for example makes { "A", "B" } out of "A,B", "A, B",
     * ""A", "B"", "'A', 'B'", depending on default delimiter and bracket set during initialization
     *
     * if it is obvious that a line is broken within a bracketed string, a line break (\n) is added to the last element
     * in the array to "mark" it as a broken entry
     *
     * @param line of text
     *
     * @return array of strings contains split line
     */
    private String[] splitLine(String line)
    {
        final ArrayList<String> rawValuesList = new ArrayList<>();

        if (bracket == null) {
            String[] split = line.split(commaDelimitator, -1);
            boolean useSame = false;
            String correctedElement = "";
            boolean first = true;
            for(String element : split) {
                if(!useSame && element.trim().startsWith("\""))
                    first = true;

                if(!useSame && element.trim().startsWith("\"")) {
                    useSame = true;
                    first = true;
                }

                // 123, "Bed&Breakfast ""Tiffany's"", Zurich", 50
                // Should become: 123 | Bed&Breakfast "Tiffany's", Zurich | 50
                // 123, "Bed&Breakfast ""Tiffany's""", 50
                // Should become: 123 | Bed&Breakfast "Tiffany's" | 50
                if(useSame && withoutQuoteLiterals(element.trim()).endsWith("\"") && (correctedElement.length() > 0 || element.trim().length() > 1)) {
                    // last (dont consider closing quote)

                    if(first)
                        correctedElement += translateQuoteLiterals(cutOffFirstQuote(cutOffLastQuote(element)));
                    else
                        correctedElement += translateQuoteLiterals(cutOffLastQuote(element));


                    rawValuesList.add(correctedElement);
                    correctedElement = "";
                    useSame = false;
                    first = false;
                    continue;
                }

                if(useSame) {
                    if(first) {
                        // first (dont consider opening quote)
                        correctedElement += translateQuoteLiterals(cutOffFirstQuote(element)) + commaDelimitator;
                        first = false;
                    }
                    else {
                        // in between (no opening/closing quotes, but add comma again (vanished by split)
                        correctedElement += translateQuoteLiterals(element) + commaDelimitator;
                    }
                }
                else {
                    if(first) {
                        rawValuesList.add(translateQuoteLiterals(cutOffFirstQuote(element)));
                    }
                    else
                        rawValuesList.add(translateQuoteLiterals(element));
                }
            }
        } else {
            int recordStart = 0;
            int recordStop = -1;
            boolean withinBrackets = false;
            for (int i = 0; i < line.length(); i++) {
                // bracket
                if (line.substring(i, i + 1).equals(bracket)) {
                    // closing -> finish recording
                    if(withinBrackets) {
                        recordStop = i;
                        withinBrackets = false;
                    }

                    // opening -> start recording
                    else {
                        recordStart = i + 1;
                        withinBrackets = true;
                    }

                    // end of line?
                    if(i == line.length() - 1) {
                  ///      System.out.println("ççç: start: " + recordStart + ", stop: " + recordStop + ", linel: " + line.length());
                        rawValuesList.add(line.substring(recordStart, recordStop));
                        recordStart = recordStop = -1;
                    }
                }

                // delim
                else if (line.substring(i, i + 1).equals(commaDelimitator)) {
                    // within brackets -> ignore
                    if(withinBrackets) {
                        // do nothing, go ahead
                    }

                    // outside -> capture last recorded value, next column ahead
                    else {
                        if(recordStart != -1) {
                            if(recordStop == -1)
                                recordStop = i;
              ///              System.out.println("ççç: start: " + recordStart + ", stop: " + recordStop + ", linel: " + line.length());
                            rawValuesList.add(line.substring(recordStart, recordStop));
                            recordStart = recordStop = -1;
                        }

                        // for subsequent delimiters without values, e.g. "abc,,,,,,def"
                        else {
                            rawValuesList.add("");
                        }

                        // in addition, if we are at line end, add one more value (the one behind the last delim)
                        if(i == line.length() - 1)
                            rawValuesList.add("");
                    }
                }

                // end of line -> capture
                else if(i == line.length() - 1) {
                    // but still within brackets -> capture until eol but mark
                    if(withinBrackets) {
             ///           System.out.println("ççç: start: " + recordStart + ", stop: " + (i+1) + ", linel: " + line.length());
                        rawValuesList.add(line.substring(recordStart, i + 1) + "\n");
                        recordStart = recordStop = -1;
                    }

                    // simply capture until eol
                    else {
               ///         System.out.println("ççç: start: " + recordStart + ", stop: " + (i+1) + ", linel: " + line.length());
                        rawValuesList.add(line.substring(recordStart, i + 1));
                        recordStart = recordStop = -1;
                    }
                }

                // non-white space character
                else if(!line.substring(i, i + 1).equals(" ")) {
                    // within brackets -> ignore
                    if(withinBrackets) {
                        // ignore
                    }

                    // outside brackets
                    else {
                        // recorder started already
                        if(recordStart > -1) {
                            // do nothing
                        }
                        else {
                            // start recording here
                            recordStart = i;
                        }
                    }
                }
            }
        }

        // return array or null
        if (rawValuesList.size() > 0) {
     ///       System.out.println("ççç: " + rawValuesList.size());
            String[] rawValues = new String[rawValuesList.size()];
            //rawValues = rawValuesList.toArray(rawValuesList);
            return rawValuesList.toArray(new String[rawValuesList.size()]);
           // String[] mock = { "abcd", "ab1d", "ab2d", "a3cd", "ab3d", "ab8d", "ab9d", "ab0d", "ascd", "awcd", "abed", "abwd", "abrd", "abtd", "azcd", "abud", "aicd", "alcd", "abkd", "abjd", "abhd", "abcg", "abfd", "abcd", "abcd", "abcs", "abad", "aycd", "abxd", "abcc", "axxd", "ayyd" };
            //return mock;
        } else {
            return null;
        }
    }

    private String cutOffFirstQuote(String s) {
        if(s.length() < 1)
            return s;

        for(String q : DEFAULT_QUOTE_LITERAL_TRANSLATION) {
            if(s.startsWith(q))
                return s.substring(1);
        }

        return s;
    }

    private String cutOffLastQuote(String s) {
        if(s.length() < 1)
            return s;

        for(String q : DEFAULT_QUOTE_LITERAL_TRANSLATION) {
            if(s.endsWith(q))
                return s.substring(0, s.length() - 1);
        }

        return s;
    }

    private String translateQuoteLiterals(String s) {
        for(int i = 0; i < DEFAULT_QUOTE_LITERALS_REGEXP.length; i ++) {
            String literal = DEFAULT_QUOTE_LITERALS_REGEXP[i];
            String translation = DEFAULT_QUOTE_LITERAL_TRANSLATION[i];
            s = s.replaceAll(literal, translation);
        }
        return s;
    }

    private String withoutQuoteLiterals(String s) {
        for(String literal : DEFAULT_QUOTE_LITERALS_REGEXP)
            s = s.replaceAll(literal, "");
        return s;
    }

    /**
     * Sometimes, values come with delimiting characters such as " or '. This method removes them.
     *
     * @param rawText is plain text
     *
     * @return text after removed quotes or apostrophes
     */
    private String unencloseText(String rawText)
    {
        String trimmed = rawText.trim();
        if (trimmed.length() > 0) {
            if ((trimmed.substring(0, 1).equals("'") && trimmed.substring(trimmed.length() - 1).equals("'"))
                    || trimmed.substring(0, 1).equals("\"") && trimmed.substring(trimmed.length() - 1).equals("\"")) {
                trimmed = trimmed.substring(1, trimmed.length() - 2);
            }
        }
        return trimmed;
    }
}
