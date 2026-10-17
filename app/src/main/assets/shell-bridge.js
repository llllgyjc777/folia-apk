// 锁屏播放桥接脚本
// 作用：把 WebView 里的播放状态推给原生（MediaSession），并接收原生指令
// 由壳层在页面加载完成后注入
//
// Folia 的真实 DOM 锚点（读源码确认，比 aria-label 稳定）：
//   [data-ponder="player-bar"]          播放条根节点
//   [data-ponder-slots]                 槽位容器
//   [data-ponder-slot="next"|"prev"]    可自定义槽位按钮（可能未启用）
//   components/FloatingPlayerControls.tsx / PlayerControlSlotButton.tsx
// 命令面板 fallback：playback-next / playback-prev，快捷键 n / b
(function () {
  if (window.__foliaShellBridgeInstalled) return;
  window.__foliaShellBridgeInstalled = true;

  var NATIVE = window.FoliaNative;
  if (!NATIVE) {
    console.warn('[ShellBridge] FoliaNative 不存在，跳过');
    return;
  }

  function log(msg) {
    try { NATIVE.log(String(msg)); } catch (e) {}
    try { console.log('[ShellBridge] ' + msg); } catch (e) {}
  }

  // ---------- 工具：找页面里的 audio 元素 ----------
  function findAudio() {
    var list = document.querySelectorAll('audio');
    for (var i = 0; i < list.length; i++) {
      var a = list[i];
      if (a.src || a.currentSrc || a.srcObject) return a;
    }
    return list.length ? list[0] : null;
  }

  // ---------- 工具：从 Folia 播放条读歌名/歌手/封面 ----------
  // 结构参考 FloatingPlayerControls.tsx：
  //   div[title={currentSong.name}]  -> 歌名
  //   播放条里 .font-bold            -> 歌名行
  //   播放条里 img                   -> 封面
  function readMeta() {
    var title = '';
    var artist = '';
    var cover = '';

    // 1) Folia 自身若设置了 navigator.mediaSession（目前没设，留作以后兜底）
    try {
      var ms = navigator.mediaSession;
      if (ms && ms.metadata) {
        title = ms.metadata.title || '';
        artist = ms.metadata.artist || '';
        var art = ms.metadata.artwork;
        if (art && art.length) cover = art[art.length - 1].src || '';
      }
    } catch (e) {}

    // 2) 精确锚点：data-ponder="player-bar"
    var bar = null;
    try {
      bar = document.querySelector('[data-ponder="player-bar"]');
    } catch (e) {}

    if (bar) {
      // 歌名：源码里给歌名节点设置了 title={currentSong.name}
      // 但播放条上每个按钮也都有 title/aria-label，所以先收集全部候选，
      // 排除按钮类文案后取「看起来像歌名」的那个。
      if (!title) {
        var all = bar.querySelectorAll('[title]:not([title=""])');
        var best = '';
        var BTN_RE = /^(播放|暂停|下一首|上一首|循环|关闭循环|开启循环|单曲循环|随机|打乱|音量|队列|喜欢|取消喜欢|歌词|时间轴|睡眠|歌单墙|搜索|设置|关闭|返回|最大化|最小化|Play|Pause|Next|Prev|Loop|Shuffle|Volume|Queue|Like|Mute)/i;
        for (var i = 0; i < all.length; i++) {
          var node = all[i];
          // 按钮上的 title 一律跳过（按钮通常带 role 或就是 button 标签）
          var tag = (node.tagName || '').toLowerCase();
          if (tag === 'button') continue;
          if (node.closest && node.closest('button')) continue;

          var tv = (node.getAttribute('title') || '').trim();
          if (!tv || tv.length > 80) continue;
          if (BTN_RE.test(tv)) continue;
          // 歌名一般比歌手长，取最长的那个候选
          if (tv.length > best.length) best = tv;
        }
        title = best;
      }
      // 歌名行：那个 font-bold 节点
      if (!title) {
        var bold = bar.querySelector('.font-bold');
        if (bold) {
          var bt = (bold.textContent || '').trim();
          if (bt && bt.length <= 80) title = bt;
        }
      }
      // 歌手：歌名之外第一个短叶子文本
      if (!artist) {
        var spans = bar.querySelectorAll('div,span,p');
        for (var k = 0; k < spans.length; k++) {
          var n = spans[k];
          if (n.children.length > 0) continue; // 只要叶子节点
          var t = (n.textContent || '').trim();
          if (!t || t.length > 60) continue;
          if (t === title) continue;
          if (/^\d{1,2}:\d{2}/.test(t)) continue; // 时间戳
          if (/^(播放|暂停|下一首|上一首|循环|关闭循环|开启循环|单曲循环|随机|打乱|音量|队列|喜欢|取消喜欢|歌词|时间轴|睡眠|歌单墙)/.test(t)) continue;
          artist = t;
          break;
        }
      }
      // 封面
      if (!cover) {
        var img = bar.querySelector('img');
        if (img && img.src) cover = img.src;
      }
    }

    // 3) 最后兜底：document.title 形式 "歌名 - 歌手"
    if (!title) {
      var dt = (document.title || '').trim();
      if (dt && dt.indexOf(' - ') > 0) {
        var parts = dt.split(' - ');
        title = parts[0].trim();
        if (!artist) artist = parts[1].trim();
      }
    }

    return { title: title, artist: artist, cover: cover };
  }

  // ---------- 定时推送状态 ----------
  var lastSig = '';
  var diagCount = 0;
  function pushState() {
    var a = findAudio();
    var meta = readMeta();
    var playing = !!(a && !a.paused && !a.ended && a.readyState > 2);
    var duration = a && isFinite(a.duration) ? Math.round(a.duration * 1000) : 0;
    var position = a && isFinite(a.currentTime) ? Math.round(a.currentTime * 1000) : 0;

    var payload = {
      title: meta.title || '',
      artist: meta.artist || '',
      album: '',
      cover: meta.cover || '',
      duration: duration,
      position: position,
      playing: playing
    };

    // 秒级签名去重，避免每 800ms 都跨进程调用
    var sig = payload.title + '|' + payload.artist + '|' + payload.playing
      + '|' + Math.floor(position / 1000) + '|' + duration;
    if (sig === lastSig) return;
    lastSig = sig;

    // 诊断：前 3 次上报若 title 为空，打印页面结构帮助定位
    if (!payload.title && diagCount < 3) {
      diagCount++;
      try {
        var bars = document.querySelectorAll('[data-ponder="player-bar"]');
        var allTitle = document.querySelectorAll('[title]:not([title=""])');
        var sample = [];
        for (var q = 0; q < Math.min(allTitle.length, 12); q++) {
          var nd = allTitle[q];
          sample.push(
            (nd.tagName || '?').toLowerCase()
            + '[title=' + (nd.getAttribute('title') || '').slice(0, 20) + ']'
            + (nd.closest && nd.closest('button') ? '{inBtn}' : '')
          );
        }
        log('DIAG bar=' + bars.length
          + ' audio=' + !!a
          + ' titled=' + allTitle.length
          + ' sample=' + sample.join(' | '));
      } catch (e) {
        log('DIAG 失败 ' + e);
      }
    }

    try {
      NATIVE.onState(JSON.stringify(payload));
    } catch (e) {
      console.warn('[ShellBridge] onState 失败', e);
    }
  }

  setInterval(pushState, 800);
  setTimeout(pushState, 1200);

  // ---------- 指令实现 ----------

  // A) 直接点 Folia 的可自定义槽位（若用户把 next/prev 放进了槽位）
  function clickSlot(actionId) {
    try {
      var btn = document.querySelector('[data-ponder-slot="' + actionId + '"]');
      if (btn && !btn.disabled) {
        btn.click();
        return true;
      }
    } catch (e) {}
    return false;
  }

  // B) 靠 aria-label / title 匹配（中英文都覆盖）
  function clickByLabel(names) {
    var nodes = document.querySelectorAll('button,[role="button"]');
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.disabled) continue;
      var label = (n.getAttribute('aria-label') || n.getAttribute('title') || n.textContent || '').trim();
      if (!label) continue;
      for (var j = 0; j < names.length; j++) {
        if (label === names[j] || label.indexOf(names[j]) > -1) {
          n.click();
          return true;
        }
      }
    }
    return false;
  }

  // C) 发送键盘事件（Folia 命令面板快捷键：n=下一首 b=上一首）
  function sendKey(key) {
    try {
      var up = key.toUpperCase();
      var code = up.charCodeAt(0);
      var targets = [document.activeElement, document.body, document.documentElement];
      for (var i = 0; i < targets.length; i++) {
        var t = targets[i];
        if (!t) continue;
        ['keydown', 'keypress', 'keyup'].forEach(function (type) {
          var ev = new KeyboardEvent(type, {
            key: key, code: 'Key' + up,
            keyCode: code, which: code,
            bubbles: true, cancelable: true
          });
          t.dispatchEvent(ev);
        });
      }
      return true;
    } catch (e) {
      return false;
    }
  }

  // E) 走命令面板执行模式：按 ':' 打开，输入 vim 快捷键，回车
  //    executeShortcut 只有命令面板打开时才生效（见 executeShortcuts.ts）
  function runViaPalette(key) {
    // 1) ':' 打开执行模式
    sendKey(':');
    log('已发送 : 打开命令面板');

    setTimeout(function () {
      // 2) 找到命令面板的输入框并输入快捷键
      var input = document.querySelector(
        'input[placeholder*="命令"], input[placeholder*="ommand"], ' +
        '[role="dialog"] input[type="text"], input[type="text"]'
      );
      if (!input) {
        log('未找到命令面板输入框，仅发送了 :');
        return;
      }
      try {
        input.focus();
        // React 受控输入：用原生 setter 赋值再派发 input 事件
        var setter = Object.getOwnPropertyDescriptor(
          window.HTMLInputElement.prototype, 'value'
        ).set;
        setter.call(input, key);
        input.dispatchEvent(new Event('input', { bubbles: true }));
        log('命令面板已输入 ' + key);
      } catch (e) {
        log('输入快捷键失败 ' + e);
        return;
      }

      // 3) 回车执行
      setTimeout(function () {
        try {
          ['keydown', 'keypress', 'keyup'].forEach(function (type) {
            input.dispatchEvent(new KeyboardEvent(type, {
              key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
              bubbles: true, cancelable: true
            }));
          });
          log('已发送回车执行 ' + key);
        } catch (e) {}
      }, 180);
    }, 220);
  }

  function doNext() {
    // 1) 优先：用户如果把 next 放进了可配置槽位
    if (clickSlot('next')) { log('next: 命中 data-ponder-slot'); return; }
    // 2) 次选：页面上真有 aria-label 是"下一首"的按钮
    if (clickByLabel(['下一首', '下一曲', 'Next track', 'Skip next'])) {
      log('next: 命中 aria-label');
      return;
    }
    // 3) 兜底：命令面板执行模式（最可靠的程序化入口）
    runViaPalette('n');
  }

  function doPrev() {
    if (clickSlot('prev')) { log('prev: 命中 data-ponder-slot'); return; }
    if (clickByLabel(['上一首', '上一曲', 'Previous track', 'Skip back'])) {
      log('prev: 命中 aria-label');
      return;
    }
    runViaPalette('b');
  }

  // ---------- 接收原生指令 ----------
  window.FoliaShell = {
    onCommand: function (cmd) {
      log('收到指令 ' + cmd);
      var a = findAudio();

      if (cmd === 'play') {
        if (a && a.src) {
          try { a.play(); } catch (e) {}
        } else {
          clickByLabel(['播放', 'Play']);
        }
        return;
      }
      if (cmd === 'pause') {
        if (a) { try { a.pause(); } catch (e) {} }
        else { clickByLabel(['暂停', 'Pause']); }
        return;
      }
      if (cmd === 'next') { doNext(); return; }
      if (cmd === 'prev') { doPrev(); return; }
      if (cmd === 'stop') {
        if (a) { try { a.pause(); } catch (e) {} }
        return;
      }
      if (cmd.indexOf('seek:') === 0) {
        var ms = parseInt(cmd.slice(5), 10);
        if (a && isFinite(ms)) { try { a.currentTime = ms / 1000; } catch (e) {} }
        return;
      }
    },

    // 供调试/未来扩展
    _debug: function () {
      return JSON.stringify({
        audio: !!findAudio(),
        meta: readMeta(),
        bar: !!document.querySelector('[data-ponder="player-bar"]'),
        slots: Array.prototype.map.call(
          document.querySelectorAll('[data-ponder-slot]'),
          function (n) { return n.getAttribute('data-ponder-slot'); }
        )
      });
    }
  };

  log('锁屏播放桥接已安装');
})();