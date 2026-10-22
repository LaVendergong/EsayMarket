package com.mall.core.pay.bill;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 行解析的小工具（账单文件里引号、BOM、反引号、# 前缀都很常见）。
 */
final class CsvLine {

    private CsvLine() {
    }

    /** 按逗号切分，支持双引号包裹（引号内的逗号不切） */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ',' && !inQuote) {
                out.add(clean(cell.toString()));
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(clean(cell.toString()));
        return out;
    }

    /** 去掉 BOM、反引号、首尾空白（微信账单每行以反引号开头） */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\uFEFF", "").replace("`", "").trim();
    }
}
