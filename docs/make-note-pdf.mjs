import { chromium } from 'playwright';
import { pathToFileURL } from 'url';
const [,, html, out, footer = 'GenAlpha — research note'] = process.argv;
const browser = await chromium.launch();
const page = await browser.newPage();
await page.emulateMedia({ media: 'print', colorScheme: 'light' });
await page.goto(pathToFileURL(html).href, { waitUntil: 'networkidle' });
await page.pdf({ path: out, format: 'A4', printBackground: true, preferCSSPageSize: true,
  margin: { top: '16mm', bottom: '16mm', left: '14mm', right: '14mm' },
  displayHeaderFooter: true, headerTemplate: '<span></span>',
  footerTemplate: '<div style="width:100%;font-family:-apple-system,sans-serif;font-size:8px;color:#9aa3a7;padding:0 14mm;display:flex;justify-content:space-between;"><span>${footer}</span><span class="pageNumber"></span></div>' });
await browser.close();
console.log('wrote', out);
