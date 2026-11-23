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
    for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    return arr;
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

    var file = new File([merged], state.name, { type: 'application/octet-stream' });
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
    }
  };

  ready(function () {
    if (!window.AndroidRoot) return;

    // 1. Add "Muat dari HP (Root)" in Card 1 of Tab 1
    var p1 = document.getElementById('p1');
    if (p1 && !document.getElementById('btn-root-load')) {
      var firstCard = p1.querySelector('.card');
      if (firstCard) {
        var loadBtn = document.createElement('button');
        loadBtn.id = 'btn-root-load';
        loadBtn.type = 'button';
        loadBtn.className = 'btn t';
        loadBtn.style.marginTop = '8px';
        loadBtn.innerHTML =
          '<svg class="ic" viewBox="0 0 24 24"><rect x="5" y="2" width="14" height="20" rx="2"/><path d="M12 18h.01M9 6h6"/></svg>' +
          '<span id="btn-root-load-txt">' + (typeof window.T === 'function' ? window.T('btnRootLoad') : 'Muat dari HP (Root)') + '</span>';
        loadBtn.onclick = function () {
          if (window.AndroidRoot && AndroidRoot.requestLoadFromPhone) {
            var res = AndroidRoot.requestLoadFromPhone();
            if (res && res.indexOf('ERR') === 0 && typeof window.log === 'function') {
              window.log(res, 'err');
            }
          }
        };
        var logoInfo = document.getElementById('logo-info');
        if (logoInfo) firstCard.insertBefore(loadBtn, logoInfo);
        else firstCard.appendChild(loadBtn);
      }
    }

    // 2. Add "Flash hasil edit ke HP (Root)" in Card 2 (#act)
    var act = document.getElementById('act');
    if (act && !document.getElementById('btn-root-flash')) {
      var flashBtn = document.createElement('button');
      flashBtn.id = 'btn-root-flash';
      flashBtn.type = 'button';
      flashBtn.className = 'btn t';
      flashBtn.innerHTML =
        '<svg class="ic" viewBox="0 0 24 24"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/></svg>' +
        '<span id="btn-root-flash-txt">' + (typeof window.T === 'function' ? window.T('btnRootFlash') : 'Flash hasil edit ke HP (Root)') + '</span>';
      flashBtn.onclick = function () {
        if (!window.__ltHasEdits) {
          if (typeof window.log === 'function' && typeof window.T === 'function') {
            window.log(window.T('noEditsYet'), 'warn');
          }
          return;
        }
        if (typeof window.run !== 'function' || typeof window.build !== 'function') return;
        window.run(async function () {
          var built = await window.build();
          var u8 = built instanceof Uint8Array ? built : new Uint8Array(built);
          await window.__ltSendBytesChunked(u8, function (sid) {
            return AndroidRoot.finishTransfer(sid, 'new_logo.img');
          });
          if (typeof window.log === 'function' && typeof window.T === 'function') {
            window.log(window.T('sentToRoot', { mb: (u8.length / 1048576).toFixed(2) }), 'ok');
          }
        });
      };
      act.appendChild(flashBtn);
    }
  });
})();
