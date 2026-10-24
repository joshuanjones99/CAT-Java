package com.verifone.cat.report.html;

/**
 * The report's embedded stylesheet, copied from the Python CAT's {@code html.py}.
 *
 * <p>Inline on purpose: the report is one self-contained file with no CDN or font fetch,
 * so it opens the same on a machine with no network.
 */
final class HtmlStyle {

    static final String CSS = """
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
                "Helvetica Neue", Arial, sans-serif;
            background: #f8f9fa;
            color: #333;
            line-height: 1.6;
        }
        .container {
            max-width: 1000px;
            margin: 0 auto;
            padding: 20px;
        }
        header {
            background: white;
            border-bottom: 3px solid #007bff;
            padding: 20px;
            margin-bottom: 20px;
            border-radius: 4px;
        }
        h1 {
            font-size: 28px;
            margin-bottom: 10px;
        }
        .metadata {
            display: grid;
            grid-template-columns: 1fr 1fr 1fr;
            gap: 20px;
            margin-top: 15px;
            font-size: 14px;
        }
        .metadata-item {
            display: flex;
            flex-direction: column;
        }
        .metadata-label {
            font-weight: bold;
            color: #666;
            font-size: 12px;
            text-transform: uppercase;
            margin-bottom: 5px;
        }
        .metadata-value {
            font-size: 16px;
            color: #333;
        }
        .summary {
            background: white;
            padding: 20px;
            margin-bottom: 20px;
            border-radius: 4px;
            display: grid;
            grid-template-columns: repeat(4, 1fr);
            gap: 15px;
        }
        .summary-card {
            text-align: center;
            padding: 15px;
            border-radius: 4px;
            border-left: 4px solid #ccc;
        }
        .summary-card.pass {
            border-left-color: #28a745;
            background: #f0f8f4;
        }
        .summary-card.fail {
            border-left-color: #dc3545;
            background: #fdf6f6;
        }
        .summary-card.warned {
            border-left-color: #ffc107;
            background: #fffbf0;
        }
        .summary-number {
            font-size: 32px;
            font-weight: bold;
            margin-bottom: 5px;
        }
        .summary-label {
            font-size: 12px;
            text-transform: uppercase;
            color: #666;
        }
        .section {
            background: white;
            padding: 20px;
            margin-bottom: 20px;
            border-radius: 4px;
        }
        .section h2 {
            font-size: 20px;
            margin-bottom: 15px;
            padding-bottom: 10px;
            border-bottom: 2px solid #e9ecef;
        }
        .test-list {
            list-style: none;
        }
        .test-item {
            padding: 12px;
            margin-bottom: 8px;
            border-left: 4px solid #ccc;
            border-radius: 2px;
            background: #fafafa;
        }
        .test-item.fail {
            border-left-color: #dc3545;
            background: #fffbfb;
        }
        .test-item.error {
            border-left-color: #fd7e14;
            background: #fffbf6;
        }
        .test-item.timeout {
            border-left-color: #ff9800;
            background: #fffbf6;
        }
        .test-item.pass {
            border-left-color: #28a745;
            background: #f9fff9;
        }
        .test-item.skipped {
            border-left-color: #999999;
            background: #f5f5f5;
        }
        .test-header {
            display: flex;
            align-items: center;
            gap: 10px;
            margin-bottom: 5px;
            font-weight: bold;
        }
        .outcome-glyph {
            font-size: 18px;
            min-width: 20px;
            text-align: center;
        }
        .test-nodeid {
            font-family: "Monaco", "Courier New", monospace;
            font-size: 13px;
            color: #666;
            margin-top: 5px;
        }
        .test-duration {
            font-size: 12px;
            color: #999;
            margin-top: 5px;
        }
        .preflight-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
            gap: 15px;
        }
        .preflight-check {
            padding: 15px;
            border-radius: 4px;
            border-left: 4px solid #ccc;
        }
        .preflight-check.pass {
            border-left-color: #28a745;
            background: #f0f8f4;
        }
        .preflight-check.fail {
            border-left-color: #dc3545;
            background: #fdf6f6;
        }
        .preflight-check.error {
            border-left-color: #fd7e14;
            background: #fffbf6;
        }
        .preflight-check.timeout {
            border-left-color: #ff9800;
            background: #fffbf6;
        }
        .preflight-check.repaired {
            border-left-color: #0275d8;
            background: #f0f5ff;
        }
        .preflight-check.unrepairable {
            border-left-color: #8b1a1a;
            background: #fff5f5;
        }
        .preflight-header {
            display: flex;
            align-items: center;
            gap: 8px;
            margin-bottom: 8px;
            font-weight: bold;
        }
        .preflight-name {
            flex: 1;
        }
        .preflight-phase {
            font-size: 11px;
            color: #999;
            text-transform: uppercase;
        }
        .preflight-detail {
            font-size: 12px;
            color: #666;
            margin-top: 5px;
        }
        .test-reason {
            margin-top: 8px;
            font-size: 12px;
        }
        .test-reason pre {
            white-space: pre-wrap;
            word-break: break-word;
            margin: 4px 0 0 0;
            padding: 8px;
            background: #f6f6f6;
            border-radius: 4px;
            color: #444;
        }
        .fake-pos-banner {
            margin: 0 0 20px 0;
            padding: 12px 16px;
            border-radius: 6px;
            border-left: 6px solid #fd7e14;
            background: #fff6ec;
            color: #7a3d00;
            font-weight: bold;
        }
        .empty {
            color: #999;
            font-style: italic;
            text-align: center;
            padding: 20px;
        }
        footer {
            text-align: center;
            font-size: 12px;
            color: #999;
            padding: 20px;
            margin-top: 40px;
        }
        """;

    private HtmlStyle() {}
}
