#!/usr/bin/env node
/* THROWAWAY — a deliberate medium-severity finding, to test whether the
 * CodeQL results check BLOCKS or merely REPORTS at that severity.
 * This branch and this file are deleted as soon as the answer is read. */
const http = require('http');
http.createServer((req, res) => {
  // js/log-injection, medium: a caller-controlled value straight into a log line
  console.log('probe request for ' + req.url);
  res.end('ok');
}).listen(9999);
