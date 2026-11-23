(function () {
  if (window.__ltRootInitialized) return;
  window.__ltRootInitialized = true;

  function ready(fn) {
    if (document.readyState !== 'loading') fn();
    else document.addEventListener('DOMContentLoaded', fn);
  }

  function b64ToBytes(b64) {
    var bin = atob(b64);
    var arr = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) {
      arr[i] = bin.charCodeAt(i);
    }
    return arr;
  }

  function bytesToB64(bytes) {
    var chunk = 0x8000;
    var parts = [];
    for (var i = 0; i < bytes.length; i += chunk) {
      parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + chunk)));
    }
    return btoa(parts.join(''));
  }

  var rxState = null;

  window.__ltBeginReceive = function (kind, fileName) {
    rxState = { kind: kind, name: fileName, chunks: [] };
  };

  window.__ltPushChunk = function (b64) {
    if (rxState) rxState.chunks.push(b64);
  };

  window.__ltFinishReceive = function () {
    if (!rxState) return;
    var state = rxState;
    rxState = null;

    var byteArrays = state.chunks.map(b64ToBytes);
    var total = 0;
    for (var i = 0; i < byteArrays.length; i++) total += byteArrays[i].length;
    var merged = new Uint8Array(total);
    var offset = 0;
    for (var j = 0; j < byteArrays.length; j++) {
      merged.set(byteArrays[j], offset);
      offset += byteArrays[j].length;
    }

    var mime = state.kind === 'bootanim' ? 'application/zip' : 'application/octet-stream';
    var file = new File([merged], state.name, { type: mime });
    var dt = new DataTransfer();
    dt.items.add(file);

    if (state.kind === 'logo') {
      var t1 = document.getElementById('t1');
      if (t1) t1.click();
      var lf = document.getElementById('lf');
      if (lf) {
        lf.files = dt.files;
        lf.dispatchEvent(new Event('change', { bubbles: true }));
        if (typeof window.run === 'function' && typeof window.loadLogo === 'function') {
          window.run(window.loadLogo);
        }
      }
    } else if (state.kind === 'bootanim') {
      var t2 = document.getElementById('t2');
      if (t2) t2.click();
      var ba = document.getElementById('ba');
      if (ba) {
        var det = ba.closest('details');
        if (det) det.open = true;
        ba.files = dt.files;
        ba.dispatchEvent(new Event('change', { bubbles: true }));
        if (typeof window.run === 'function' && typeof window.loadBa === 'function') {
          window.run(window.loadBa);
        }
      }
    }
  };

  window.__ltSyncTheme = function (isDark) {
    var mode = isDark ? 'dark' : 'light';
    document.documentElement.dataset.theme = mode;
    var tc = document.getElementById('tc');
    if (tc) tc.content = isDark ? '#0B1015' : '#F1F5F9';
    try {
      localStorage.setItem('lt-theme', mode);
    } catch (e) {}
  };

  ready(function () {
    if (!window.AndroidRoot) return;

    // 1. Add "Muat dari HP (Root)" button in Card 1 of Tab 1
    var p1 = document.getElementById('p1');
    if (p1 && !document.getElementById('btn-root-load')) {
      var firstCard = p1.querySelector('.card');
      if (firstCard) {
        var loadBtn = document.createElement('button');
        loadBtn.id = 'btn-root-load';
        loadBtn.type = 'button';
        loadBtn.className = 'btn t';
        loadBtn.innerHTML =
          '<svg class="ic" viewBox="0 0 24 24"><rect x="5" y="2" width="14" height="20" rx="2"/><path d="M12 18h.01M9 6h6"/></svg>' +
          '<span>Muat dari HP (Root)</span>';
        loadBtn.onclick = function () {
          if (window.AndroidRoot && AndroidRoot.requestLoadFromPhone) {
            var res = AndroidRoot.requestLoadFromPhone();
            if (res && res.indexOf('ERR') === 0 && typeof window.log === 'function') {
              window.log('GAGAL: ' + res);
            }
          }
        };
        firstCard.appendChild(loadBtn);
      }
    }

    // 2. Add "Flash hasil edit ke HP (Root)" button in Card 2 (#act)
    var act = document.getElementById('act');
    if (act && !document.getElementById('btn-root-flash')) {
      var flashBtn = document.createElement('button');
      flashBtn.id = 'btn-root-flash';
      flashBtn.type = 'button';
      flashBtn.className = 'btn t';
      flashBtn.innerHTML =
        '<svg class="ic" viewBox="0 0 24 24"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/></svg>' +
        '<span>Flash hasil edit ke HP (Root)</span>';
      flashBtn.onclick = function () {
        if (typeof window.run !== 'function' || typeof window.build !== 'function') return;
        window.run(async function () {
          var built = await window.build();
          var u8 = built instanceof Uint8Array ? built : new Uint8Array(built);
          var startRes = AndroidRoot.flashBegin('new_logo.img');
          if (startRes && startRes.indexOf('ERR') === 0) {
            throw new Error(startRes);
          }
          var step = 196608; // ~256 KB base64 per IPC call
          for (var off = 0; off < u8.length; off += step) {
            var slice = u8.subarray(off, Math.min(off + step, u8.length));
            var cRes = AndroidRoot.flashChunk(bytesToB64(slice));
            if (cRes && cRes.indexOf('ERR') === 0) {
              throw new Error(cRes);
            }
          }
          var endRes = AndroidRoot.flashCommit();
          if (endRes && endRes.indexOf('ERR') === 0) {
            throw new Error(endRes);
          }
          if (typeof window.log === 'function') {
            window.log('Dikirim ke Root Tool untuk di-flash (' + (u8.length / 1048576).toFixed(2) + ' MB)');
          }
        });
      };
      act.appendChild(flashBtn);
    }
  });
})();
