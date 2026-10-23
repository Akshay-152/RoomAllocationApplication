import zipfile, os

# Exam-data fixture: valid rows + one row with an unparseable date (validation test).
ROWS = [
    ["subject", "branch", "batch", "date", "time"],
    ["Mathematics", None, None, "05/12/2026", "10:00"],   # clashes with Physics (same slot)
    ["Physics", None, None, "05/12/2026", "10:00"],       # clashes with Mathematics
    ["Chemistry", "MEC", "B", "06/12/2026", "14:00"],
    ["History", "MEC", "B", "99/99/2026", "09:00"],       # invalid date -> rejected
]

def col_letter(i):
    return chr(ord('A') + i)

def cell_xml(ref, value):
    if value is None or value == "":
        return f'<c r="{ref}"/>'
    try:
        int(value)
        return f'<c r="{ref}"><v>{value}</v></c>'
    except ValueError:
        return f'<c r="{ref}" t="inlineStr"><is><t>{value}</t></is></c>'

sheet_rows = []
for r, row in enumerate(ROWS, start=1):
    cells = "".join(cell_xml(f"{col_letter(c)}{r}", v) for c, v in enumerate(row))
    sheet_rows.append(f'<row r="{r}">{cells}</row>')

sheet = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
         '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
         '<sheetData>' + "".join(sheet_rows) + '</sheetData></worksheet>')

workbook = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
            '<sheets><sheet name="Exams" sheetId="1" r:id="rId1"/></sheets></workbook>')

wb_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
           '<Relationship Id="rId1" '
           'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
           'Target="worksheets/sheet1.xml"/>'
           '<Relationship Id="rId2" '
           'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" '
           'Target="styles.xml"/></Relationships>')

root_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
             '<Relationship Id="rId1" '
             'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
             'Target="xl/workbook.xml"/></Relationships>')

content_types = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                 '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                 '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                 '<Default Extension="xml" ContentType="application/xml"/>'
                 '<Override PartName="/xl/workbook.xml" '
                 'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                 '<Override PartName="/xl/worksheets/sheet1.xml" '
                 'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
                 '<Override PartName="/xl/styles.xml" '
                 'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
                 '</Types>')

styles = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
          '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
          '<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>'
          '<fills count="2"><fill><patternFill patternType="none"/></fill>'
          '<fill><patternFill patternType="gray125"/></fill></fills>'
          '<borders count="1"><border/></borders>'
          '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
          '<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>'
          '</styleSheet>')

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test-exams.xlsx")
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml", content_types)
    z.writestr("_rels/.rels", root_rels)
    z.writestr("xl/workbook.xml", workbook)
    z.writestr("xl/_rels/workbook.xml.rels", wb_rels)
    z.writestr("xl/styles.xml", styles)
    z.writestr("xl/worksheets/sheet1.xml", sheet)

print("wrote", out)
