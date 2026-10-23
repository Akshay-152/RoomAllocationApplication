import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** Prints the raw text extraction used by the importer (for debugging). */
public class ExtractPdf {
    public static void main(String[] args) throws Exception {
        try (PDDocument doc = PDDocument.load(new java.io.File(args[0]))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            for (String line : text.split("\\r\\n|\\n|\\r")) {
                System.out.println("[" + line + "]");
            }
        }
    }
}
