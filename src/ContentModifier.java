import java.util.ArrayList;
import java.util.List;

public class ContentModifier {


    /**
     * modify the lines of text to replace smiley with Trolly, and Stockholm with Linkoping.
     *
     * @return the modified size of all the text.
     */
    public static int handleText(List<String> lines) {
        List<String> alteredLines = new ArrayList<>();
        int offset = 0;
        for (String s : lines) {
            offset += getAlteredContentLength(s);
            String alteredContent = s.replace("Smiley", "Trolly").replace("Stockholm", "Linköping");
            alteredLines.add(alteredContent);
        }
        lines.clear();
        lines.addAll(alteredLines);
        return offset;
    }

    private static int getAlteredContentLength(String string) {
        char[] chars = string.toCharArray();
        String newString = "";
        int lengthModified = 0;
        for (int i = 0; i < chars.length; i++) {
            newString += chars[i];
            if (isStockholm(newString)) {
                lengthModified += 2;
                newString = "";
            }
        }
        return lengthModified;
    }

    private static boolean isStockholm(String s) {
        return s.contains("Stockholm");
    }

    public static void modifyContentLength(List<String> lines, int offset) {
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            if (text.contains("Content-Length:")) {
                int contentLength = getContentLength(text);
                contentLength += offset;
                String modified = "Content-Length: " + contentLength + "\r\n";
                lines.set(i, modified);
            }
        }
    }

    public static Integer getContentLength(String header) {
        if (header.startsWith("Content-Length:")) {
            return Integer.parseInt(header.split(":")[1].trim());
        }
        return null;
    }
}
