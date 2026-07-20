// Render manual.html → Operators-Manual.pdf using Playwright's bundled Chromium.
// Run from ops/e2e (where playwright is installed):
//   node docs/manual/make-pdf.mjs
import { chromium } from 'playwright';
import { pathToFileURL } from 'url';
import path from 'path';

const root = path.resolve(process.argv[2] || '.');
const htmlPath = path.join(root, 'docs/manual/manual.html');
const outPath = path.join(root, 'docs/manual/Operators-Manual.pdf');

const browser = await chromium.launch();
const page = await browser.newPage();
await page.emulateMedia({ media: 'print', colorScheme: 'light' });
await page.goto(pathToFileURL(htmlPath).href, { waitUntil: 'networkidle' });
await page.pdf({
  path: outPath,
  format: 'A4',
  printBackground: true,
  preferCSSPageSize: true,
  margin: { top: '16mm', bottom: '16mm', left: '14mm', right: '14mm' },
  displayHeaderFooter: true,
  headerTemplate: '<span></span>',
  footerTemplate:
    '<div style="width:100%;font-family:-apple-system,sans-serif;font-size:8px;color:#9aa3a7;padding:0 14mm;display:flex;justify-content:space-between;">'
    + '<span>Verify Everything — A Build Memoir</span>'
    + '<span class="pageNumber"></span></div>',
});
await browser.close();
console.log('wrote', outPath);
