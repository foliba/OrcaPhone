const { chromium } = require('playwright');
(async () => {
  const dir = process.argv[2];
  const b = await chromium.launch();
  const p = await b.newPage({ deviceScaleFactor: 1 });
  await p.setViewportSize({ width: 512, height: 512 });
  await p.goto('file://' + dir + '/icon.html');
  await p.screenshot({ path: dir + '/icon.png', clip: { x: 0, y: 0, width: 512, height: 512 } });
  for (const l of ['en-US', 'de-DE']) {
    await p.setViewportSize({ width: 1024, height: 500 });
    await p.goto('file://' + dir + '/feature_' + l + '.html');
    await p.screenshot({ path: dir + '/feature_' + l + '.png', clip: { x: 0, y: 0, width: 1024, height: 500 } });
  }
  await b.close();
})();
