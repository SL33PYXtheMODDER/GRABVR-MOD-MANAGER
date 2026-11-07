'use strict';
// window.grabvr, backed by the Kotlin side.
(() => {
  const native = () => window.GrabvrNative;
  Object.defineProperty(window, '__nativeFound', { get: () => !!native() });
  let next = 1;
  const waiting = new Map();
  const listeners = new Set();
  const call = (method, ...args) => new Promise((resolve, reject) => {
    const id = next++;
    waiting.set(id, { resolve, reject });
    native().call(id, method, JSON.stringify(args));
    setTimeout(() => {
      if (waiting.has(id)) listeners.forEach((cb) => cb('Still waiting on native "' + method + '" - is it implemented on the Kotlin side?'));
    }, 8000);
  });
  window.__gvDone = (id, ok, value) => {
    const w = waiting.get(id);
    if (!w) return;
    waiting.delete(id);
    if (ok) w.resolve(value); else w.reject(new Error(value));
  };
  window.__gvProgress = (p) => listeners.forEach((cb) => cb(p));
  window.grabvr = {
    state: () => call('state'),
    pair: (port, code, connectPort) => call('pair', port, code, connectPort),
    pairingPort: () => call('pairingPort'),
    reconnect: () => call('reconnect'),
    setAutoReconnect: (on) => call('setAutoReconnect', on),
    openDevSettings: () => call('openDevSettings'),
    launch: () => call('launch'),
    stopGame: () => call('stopGame'),
    pullBase: () => call('pullBase'),
    saveOriginal: () => call('saveOriginal'),   // copy the installed GRAB APK(s) to app storage
    chooseBase: () => call('chooseBase'),
    restoreBase: () => call('restoreBase'),
    toggle: (id) => call('toggle', id),
    setMany: (ids, on) => call('setMany', ids, on),
    remove: (id) => call('remove', id),
    storeConfig: () => call('storeConfig'),
    storeSetUrl: (url) => call('storeSetUrl', url),
    storeList: (opts) => call('storeList', opts),
    storeInstall: (modId) => call('storeInstall', modId),
    installLocal: () => call('installLocal'),
    apply: () => call('apply'),
    // Levels: user picks .level files, Kotlin pushes them over ADB.
    listLevels: () => call('listLevels'),            // -> [{name, size}]
    importLevels: () => call('importLevels'),        // -> {imported:[name], failed:[{name,error}]}
    removeLevel: (name) => call('removeLevel', name),
    onProgress: (cb) => { listeners.add(cb); return () => listeners.delete(cb); },
  };
})();
