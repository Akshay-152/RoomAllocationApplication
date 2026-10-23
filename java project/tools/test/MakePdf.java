import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/** Builds test-students.pdf: a simple table with some missing cells. */
public class MakePdf {

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "test-students.pdf";
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 780;
                line(cs, 60, y, "Roll No", "Name", "Branch", "Batch");
                line(cs, 60, y - 20, "401", "Sanjay Iyer", "CEC", "A");
                line(cs, 60, y - 40, "402", "Asha", "", "A");        // missing branch
                line(cs, 60, y - 60, "403", "", "", "");             // only roll no
                line(cs, 60, y - 80, "404", "Ravi", "ECE", "");      // missing batch
            }
            doc.save(out);
        }
        System.out.println("wrote " + out);
    }

    /** Draws four columns with wide gaps (like a real table). */
    private static void line(PDPageContentStream cs, float x, float top,
                             String a, String b, String c, String d) throws Exception {
        float[] xs = {x, x + 70, x + 220, x + 320};
        String[] vals = {a, b, c, d};
        for (int i = 0; i < 4; i++) {
            if (vals[i] == null || vals[i].isEmpty()) continue;
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA, 11);
            cs.newLineAtOffset(xs[i], top);
            cs.showText(vals[i]);
            cs.endText();
        }
    }
}
