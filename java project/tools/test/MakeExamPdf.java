import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * Builds test-exams.pdf: an exam schedule table with some missing cells and
 * one row with an unparseable date (validation test).
 */
public class MakeExamPdf {

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "test-exams.pdf";
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 780;
                line(cs, 60, y, "Subject", "Branch", "Batch", "Date", "Time");
                line(cs, 60, y - 22, "Mathematics", "", "", "05/12/2026", "10:00");
                line(cs, 60, y - 44, "Physics", "", "", "05/12/2026", "10:00");
                line(cs, 60, y - 66, "Chemistry", "MEC", "B", "06/12/2026", "14:00");
                line(cs, 60, y - 88, "History", "MEC", "B", "99/99/2026", "09:00");
            }
            doc.save(out);
        }
        System.out.println("wrote " + out);
    }

    /** Draws five columns with wide gaps (like a real table). */
    private static void line(PDPageContentStream cs, float x, float top,
                             String a, String b, String c, String d, String e) throws Exception {
        float[] xs = {x, x + 160, x + 240, x + 300, x + 410};
        String[] vals = {a, b, c, d, e};
        for (int i = 0; i < 5; i++) {
            if (vals[i] == null || vals[i].isEmpty()) continue;
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA, 11);
            cs.newLineAtOffset(xs[i], top);
            cs.showText(vals[i]);
            cs.endText();
        }
    }
}
