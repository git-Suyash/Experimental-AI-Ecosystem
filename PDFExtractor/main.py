
"""
PDF Data Extractor
==================
Extracts text, tables, hyperlinks, and metadata from a PDF into a Markdown file.
 
Usage:
    python extract_pdf.py <input.pdf> [output.md]
 
Requirements:
    pip install pdfplumber pypdf
"""
 
import sys
import pdfplumber
from pypdf import PdfReader
 
 
def extract_metadata(pdf_path: str) -> dict:
    """Extract PDF metadata using pypdf."""
    reader = PdfReader(pdf_path)
    meta = reader.metadata or {}
    return {
        "Title":    meta.get("/Title", "N/A"),
        "Author":   meta.get("/Author", "N/A"),
        "Subject":  meta.get("/Subject", "N/A"),
        "Creator":  meta.get("/Creator", "N/A"),
        "Producer": meta.get("/Producer", "N/A"),
        "Pages":    str(len(reader.pages)),
    }
 
 
def extract_links(pdf_path: str) -> dict[int, list[str]]:
    """Extract hyperlinks per page using pypdf annotations."""
    reader = PdfReader(pdf_path)
    links: dict[int, list[str]] = {}
 
    for page_num, page in enumerate(reader.pages, start=1):
        page_links = []
        annots = page.get("/Annots")
        if annots:
            for annot_ref in annots:
                try:
                    annot = annot_ref.get_object()
                    subtype = annot.get("/Subtype")
                    if subtype == "/Link":
                        action = annot.get("/A")
                        if action:
                            uri = action.get("/URI")
                            if uri:
                                page_links.append(str(uri))
                        dest = annot.get("/Dest")
                        if dest and not action:
                            page_links.append(f"[Internal link → {dest}]")
                except Exception:
                    pass
        if page_links:
            links[page_num] = page_links
 
    return links
 
 
def format_table_md(table: list[list]) -> str:
    """Render a table as a Markdown table."""
    if not table:
        return ""
 
    # Sanitise cells: strip newlines and escape pipe characters
    cleaned = [
        [str(cell).replace("\n", " ").replace("|", "\\|") if cell is not None else ""
         for cell in row]
        for row in table
    ]
 
    col_count = max(len(row) for row in cleaned)
 
    def fmt_row(row):
        cells = [row[i] if i < len(row) else "" for i in range(col_count)]
        return "| " + " | ".join(cells) + " |"
 
    separator = "| " + " | ".join(["---"] * col_count) + " |"
    lines = [fmt_row(cleaned[0]), separator]
    for row in cleaned[1:]:
        lines.append(fmt_row(row))
    return "\n".join(lines)
 
 
def extract_all(pdf_path: str, output_path: str):
    """Main extraction routine — writes everything to a Markdown file."""
 
    print(f"Reading: {pdf_path}")
    metadata = extract_metadata(pdf_path)
    all_links = extract_links(pdf_path)
 
    with open(output_path, "w", encoding="utf-8") as out:
 
        # ── Title ────────────────────────────────────────────────────────────
        out.write("# PDF Extraction Report\n\n")
 
        # ── Metadata ─────────────────────────────────────────────────────────
        out.write("## Document Metadata\n\n")
        out.write("| Field | Value |\n")
        out.write("| --- | --- |\n")
        for key, val in metadata.items():
            out.write(f"| **{key}** | {val} |\n")
        out.write("\n---\n\n")
 
        # ── Per-page content ──────────────────────────────────────────────────
        with pdfplumber.open(pdf_path) as pdf:
            total_pages = len(pdf.pages)
            total_tables = 0
 
            for page_num, page in enumerate(pdf.pages, start=1):
                out.write(f"## Page {page_num} of {total_pages}\n\n")
 
                # ── Text ──────────────────────────────────────────────────────
                out.write("### Text\n\n")
                text = page.extract_text(x_tolerance=3, y_tolerance=3) or ""
                if text.strip():
                    out.write(text.strip() + "\n\n")
                else:
                    out.write("_No text detected on this page._\n\n")
 
                # ── Tables ────────────────────────────────────────────────────
                tables = page.extract_tables()
                if tables:
                    out.write(f"### Tables ({len(tables)} found)\n\n")
                    for t_idx, table in enumerate(tables, start=1):
                        total_tables += 1
                        out.write(f"**Table {t_idx}**\n\n")
                        out.write(format_table_md(table) + "\n\n")
                else:
                    out.write("### Tables\n\n_No tables detected on this page._\n\n")
 
                # ── Links ─────────────────────────────────────────────────────
                page_links = all_links.get(page_num, [])
                if page_links:
                    out.write(f"### Links ({len(page_links)} found)\n\n")
                    for link in page_links:
                        if link.startswith("http"):
                            out.write(f"- <{link}>\n")
                        else:
                            out.write(f"- {link}\n")
                    out.write("\n")
                else:
                    out.write("### Links\n\n_No links detected on this page._\n\n")
 
                out.write("---\n\n")
 
        # ── Summary ───────────────────────────────────────────────────────────
        link_count = sum(len(v) for v in all_links.values())
        out.write("## Extraction Summary\n\n")
        out.write("| Metric | Count |\n")
        out.write("| --- | --- |\n")
        out.write(f"| Total pages | {total_pages} |\n")
        out.write(f"| Total tables | {total_tables} |\n")
        out.write(f"| Total links | {link_count} |\n")
 
    print(f"Done! Output written to: {output_path}")
    print(f"  Pages: {total_pages} | Tables: {total_tables} | Links: {link_count}")
 
 

# ── Entry point ──────────────────────────────────────────────────────────────

if __name__ == "__main__":
    
    input_pdf = "./data/Summary-of-Basic-Coverage-PlanOption1.pdf"
    output_txt = "./output.md"
    extract_all(input_pdf, output_txt)