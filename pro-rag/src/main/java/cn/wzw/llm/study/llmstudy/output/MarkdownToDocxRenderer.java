package cn.wzw.llm.study.llmstudy.output;

import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 GFM Markdown 渲染到已有的 XWPFDocument 中。
 * 无状态，线程安全，可复用。
 * <p>
 * 支持：H1-H6、加粗/斜体/加粗+斜体、行内代码、无序列表、有序列表、
 * GFM 表格、引用块、围栏代码块、水平分割线、空行分段。
 */
public class MarkdownToDocxRenderer {

    private static final int[] HEADING_SIZES = {22, 16, 14, 12, 11, 10};

    // ---- 块级正则 ----
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^(---+|\\*\\*\\*+|___+)\\s*$");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.+)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\d+)\\.\\s+(.+)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|?[\\s:]*-+[\\s:|-]*$");
    private static final Pattern TABLE_PIPE = Pattern.compile("^\\|?.+\\|?$");

    // ---- 行内格式正则（顺序 = 特异度：***  > **  > *  > `） ----
    private static final Pattern INLINE_FORMAT = Pattern.compile(
            "(\\*\\*\\*(.+?)\\*\\*\\*)"   // 1,2: bold+italic
                    + "|(\\*\\*(.+?)\\*\\*)"    // 3,4: bold
                    + "|(\\*(.+?)\\*)"          // 5,6: italic
                    + "|(`(.+?)`)"              // 7,8: inline code
    );

    /**
     * 将 Markdown 内容追加渲染到 document 末尾。
     */
    public void render(XWPFDocument document, String markdown) {
        if (markdown == null || markdown.isBlank()) {
            document.createParagraph();
            return;
        }

        String[] rawLines = markdown.replace("\r\n", "\n").split("\n", -1);
        boolean inCodeBlock = false;
        StringBuilder codeBuffer = new StringBuilder();
        String codeLanguage = "";

        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            String stripped = line.strip();

            // ---- 代码块状态 ----
            if (stripped.startsWith("```")) {
                if (inCodeBlock) {
                    renderCodeBlock(document, codeBuffer.toString(), codeLanguage);
                    codeBuffer.setLength(0);
                    inCodeBlock = false;
                } else {
                    inCodeBlock = true;
                    codeLanguage = stripped.length() > 3 ? stripped.substring(3).trim() : "";
                }
                continue;
            }
            if (inCodeBlock) {
                if (codeBuffer.length() > 0) {
                    codeBuffer.append('\n');
                }
                codeBuffer.append(line);
                continue;
            }

            // ---- 表格（前瞻检测：当前行含 | 且下一行是分隔行） ----
            if (stripped.startsWith("|") && i + 1 < rawLines.length
                    && TABLE_SEPARATOR.matcher(rawLines[i + 1].strip()).matches()) {
                int end = collectTableLines(rawLines, i);
                renderTable(document, Arrays.copyOfRange(rawLines, i, end));
                i = end - 1;
                continue;
            }

            // ---- 标题 ----
            Matcher headingMatch = HEADING.matcher(stripped);
            if (headingMatch.matches()) {
                int level = headingMatch.group(1).length();
                renderHeading(document, headingMatch.group(2), level);
                continue;
            }

            // ---- 水平分割线 ----
            if (HORIZONTAL_RULE.matcher(stripped).matches()) {
                renderHorizontalRule(document);
                continue;
            }

            // ---- 引用 ----
            Matcher bqMatch = BLOCKQUOTE.matcher(stripped);
            if (bqMatch.matches()) {
                renderBlockquote(document, bqMatch.group(1));
                continue;
            }

            // ---- 无序列表 ----
            Matcher bulletMatch = BULLET.matcher(stripped);
            if (bulletMatch.matches()) {
                renderBulletItem(document, bulletMatch.group(1));
                continue;
            }

            // ---- 有序列表 ----
            Matcher orderedMatch = ORDERED.matcher(stripped);
            if (orderedMatch.matches()) {
                renderOrderedItem(document, orderedMatch.group(2), orderedMatch.group(1));
                continue;
            }

            // ---- 空行 ----
            if (stripped.isEmpty()) {
                document.createParagraph();
                continue;
            }

            // ---- 默认：普通段落（含行内格式） ----
            renderParagraph(document, stripped);
        }

        // 兜底：未闭合的代码块
        if (inCodeBlock && codeBuffer.length() > 0) {
            renderCodeBlock(document, codeBuffer.toString(), codeLanguage);
        }
    }

    // ==================== 块级渲染 ====================

    private void renderHeading(XWPFDocument document, String text, int level) {
        int idx = Math.min(level, HEADING_SIZES.length) - 1;
        int fontSize = HEADING_SIZES[idx];
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(200);
        paragraph.setSpacingAfter(100);
        addInlineFormattedRuns(paragraph, text, true, false, fontSize);
    }

    private void renderParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        addInlineFormattedRuns(paragraph, text, false, false, 11);
    }

    private void renderBulletItem(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(360);
        XWPFRun bullet = paragraph.createRun();
        bullet.setText("• ");
        bullet.setFontSize(11);
        addInlineFormattedRuns(paragraph, text, false, false, 11);
    }

    private void renderOrderedItem(XWPFDocument document, String text, String number) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(360);
        XWPFRun prefix = paragraph.createRun();
        prefix.setText(number + ". ");
        prefix.setFontSize(11);
        addInlineFormattedRuns(paragraph, text, false, false, 11);
    }

    private void renderBlockquote(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(720);
        paragraph.setBorderLeft(Borders.SINGLE);
        addInlineFormattedRuns(paragraph, text, false, false, 11, "666666");
    }

    private void renderHorizontalRule(XWPFDocument document) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setBorderBottom(Borders.SINGLE);
        paragraph.setAlignment(ParagraphAlignment.CENTER);
    }

    private void renderCodeBlock(XWPFDocument document, String code, String language) {
        String[] codeLines = code.split("\n", -1);
        for (String codeLine : codeLines) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setIndentationLeft(360);
            XWPFRun run = paragraph.createRun();
            run.setText(codeLine);
            run.setFontFamily("Consolas");
            run.setFontSize(10);
        }
    }

    private void renderTable(XWPFDocument document, String[] tableLines) {
        if (tableLines.length < 2) {
            return;
        }

        String[] headerCells = splitTableRow(tableLines[0]);
        int maxCols = headerCells.length;

        // 计算数据行（跳过分隔行）
        int dataStart = 1;
        if (tableLines.length > 1 && TABLE_SEPARATOR.matcher(tableLines[1].strip()).matches()) {
            dataStart = 2;
        }

        int rowCount = 1 + (tableLines.length - dataStart);
        XWPFTable table = document.createTable(rowCount, maxCols);
        table.setWidth("100%");

        // 表头
        XWPFTableRow headerRow = table.getRow(0);
        for (int c = 0; c < headerCells.length; c++) {
            XWPFTableCell cell = headerRow.getCell(c);
            clearCellAddBold(cell, headerCells[c].trim());
        }

        // 数据行
        for (int r = dataStart; r < tableLines.length; r++) {
            String[] cells = splitTableRow(tableLines[r]);
            XWPFTableRow row = table.getRow(r - dataStart + 1);
            for (int c = 0; c < Math.min(cells.length, maxCols); c++) {
                XWPFTableCell cell = row.getCell(c);
                clearCellAddText(cell, cells[c].trim());
            }
        }

        // 边框
        applyTableBorders(table);
    }

    // ==================== 行内格式 ====================

    /**
     * 将含行内 Markdown 格式的文本拆分为多个 XWPFRun（加粗/斜体/行内代码）。
     */
    private void addInlineFormattedRuns(XWPFParagraph paragraph, String text,
                                        boolean boldBase, boolean italicBase, int fontSize) {
        addInlineFormattedRuns(paragraph, text, boldBase, italicBase, fontSize, null);
    }

    private void addInlineFormattedRuns(XWPFParagraph paragraph, String text,
                                        boolean boldBase, boolean italicBase, int fontSize,
                                        String color) {
        Matcher matcher = INLINE_FORMAT.matcher(text);
        int lastEnd = 0;
        boolean foundAny = false;

        while (matcher.find()) {
            foundAny = true;
            // 匹配前的普通文本
            if (matcher.start() > lastEnd) {
                createFormatRun(paragraph, text.substring(lastEnd, matcher.start()),
                        boldBase, italicBase, fontSize, color);
            }

            // 匹配到的格式文本
            if (matcher.group(1) != null) {
                // ***bold+italic***
                createFormatRun(paragraph, matcher.group(2), true, true, fontSize, color);
            } else if (matcher.group(3) != null) {
                // **bold**
                createFormatRun(paragraph, matcher.group(4), true, false, fontSize, color);
            } else if (matcher.group(5) != null) {
                // *italic*
                createFormatRun(paragraph, matcher.group(6), false, true, fontSize, color);
            } else if (matcher.group(7) != null) {
                // `inline code`
                createCodeRun(paragraph, matcher.group(8));
            }
            lastEnd = matcher.end();
        }

        if (!foundAny) {
            // 没有行内格式，整段作为普通文本
            createFormatRun(paragraph, text, boldBase, italicBase, fontSize, color);
        } else if (lastEnd < text.length()) {
            // 尾部剩余文本
            createFormatRun(paragraph, text.substring(lastEnd), boldBase, italicBase, fontSize, color);
        }
    }

    private void createFormatRun(XWPFParagraph paragraph, String text, boolean bold,
                                 boolean italic, int fontSize, String color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setItalic(italic);
        run.setFontSize(fontSize);
        if (color != null) {
            run.setColor(color);
        }
    }

    private void createCodeRun(XWPFParagraph paragraph, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontFamily("Consolas");
        run.setFontSize(10);
    }

    // ==================== 表格辅助 ====================

    private int collectTableLines(String[] rawLines, int start) {
        int end = start + 2; // 至少 header + separator
        while (end < rawLines.length && TABLE_PIPE.matcher(rawLines[end].strip()).matches()) {
            end++;
        }
        return end;
    }

    private String[] splitTableRow(String row) {
        String stripped = row.strip();
        if (stripped.startsWith("|")) {
            stripped = stripped.substring(1);
        }
        if (stripped.endsWith("|")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped.split("\\|");
    }

    private void clearCellAddBold(XWPFTableCell cell, String text) {
        XWPFParagraph paragraph = cell.getParagraphs().get(0);
        // 清除默认空 run
        if (!paragraph.getRuns().isEmpty()) {
            paragraph.removeRun(0);
        }
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(11);
    }

    private void clearCellAddText(XWPFTableCell cell, String text) {
        XWPFParagraph paragraph = cell.getParagraphs().get(0);
        if (!paragraph.getRuns().isEmpty()) {
            paragraph.removeRun(0);
        }
        addInlineFormattedRuns(paragraph, text, false, false, 11);
    }

    private void applyTableBorders(XWPFTable table) {
        try {
            CTTblBorders borders = table.getCTTbl().getTblPr().addNewTblBorders();
            borders.addNewTop().setVal(STBorder.SINGLE);
            borders.addNewBottom().setVal(STBorder.SINGLE);
            borders.addNewLeft().setVal(STBorder.SINGLE);
            borders.addNewRight().setVal(STBorder.SINGLE);
            borders.addNewInsideH().setVal(STBorder.SINGLE);
            borders.addNewInsideV().setVal(STBorder.SINGLE);
        } catch (Exception e) {
            // poi-ooxml-lite 可能缺少部分 CT 类，优雅降级（表格仍可用，只是无显式边框）
        }
    }
}
