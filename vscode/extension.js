'use strict';

const vscode = require('vscode');
const cp = require('child_process');
const fs = require('fs');
const path = require('path');
const readline = require('readline');

function javaMajor(executable) {
  try {
    const result = cp.spawnSync(executable, ['-version'], { encoding: 'utf8', windowsHide: true, timeout: 5000 });
    const output = `${result.stdout || ''}\n${result.stderr || ''}`;
    const match = output.match(/version\s+"(\d+)(?:\.(\d+))?/i);
    if (!match) return 0;
    return Number(match[1]) === 1 ? Number(match[2]) : Number(match[1]);
  } catch (_) {
    return 0;
  }
}

function javaExecutablesBelow(root, depth = 2) {
  if (!root || !fs.existsSync(root)) return [];
  const executableName = process.platform === 'win32' ? 'java.exe' : 'java';
  const found = [];
  const visit = (directory, remaining) => {
    const direct = path.join(directory, 'bin', executableName);
    if (fs.existsSync(direct)) found.push(direct);
    if (remaining <= 0) return;
    let entries;
    try { entries = fs.readdirSync(directory, { withFileTypes: true }); } catch (_) { return; }
    for (const entry of entries) {
      if (entry.isDirectory()) visit(path.join(directory, entry.name), remaining - 1);
    }
  };
  visit(root, depth);
  return found;
}

function discoverJava(configured) {
  // A non-default value is an explicit user override; report it directly if invalid.
  if (configured && configured !== 'java') {
    const major = javaMajor(configured);
    if (major >= 17) return configured;
    throw new Error(`Configured Java executable is Java ${major || 'unknown'}; Java 17 or newer is required: ${configured}`);
  }

  const executableName = process.platform === 'win32' ? 'java.exe' : 'java';
  const candidates = [];
  if (process.env.JAVA_HOME) candidates.push(path.join(process.env.JAVA_HOME, 'bin', executableName));
  candidates.push('java');

  if (process.platform === 'win32') {
    const roots = [
      path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Java'),
      path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Eclipse Adoptium'),
      path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Microsoft'),
      path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Amazon Corretto'),
      path.join(process.env.ProgramFiles || 'C:\\Program Files', 'BellSoft'),
      path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Zulu'),
      path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Apache NetBeans', 'jdk'),
      process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Programs')
    ];
    for (const root of roots) candidates.push(...javaExecutablesBelow(root, 2));
    try {
      const where = cp.spawnSync('where.exe', ['java'], { encoding: 'utf8', windowsHide: true, timeout: 5000 });
      candidates.push(...String(where.stdout || '').split(/\r?\n/).filter(Boolean));
    } catch (_) {}
  } else if (process.platform === 'darwin') {
    try {
      const home = cp.spawnSync('/usr/libexec/java_home', ['-v', '17+'], { encoding: 'utf8', timeout: 5000 });
      if (home.status === 0) candidates.push(path.join(home.stdout.trim(), 'bin', 'java'));
    } catch (_) {}
    candidates.push(...javaExecutablesBelow('/Library/Java/JavaVirtualMachines', 3));
  } else {
    candidates.push(...javaExecutablesBelow('/usr/lib/jvm', 2));
    candidates.push(...javaExecutablesBelow('/opt', 2));
  }

  const checked = new Set();
  for (const candidate of candidates) {
    if (!candidate || checked.has(candidate)) continue;
    checked.add(candidate);
    if (javaMajor(candidate) >= 17) return candidate;
  }
  throw new Error('Java 17 or newer was not found. Install a Java 17+ runtime; the extension will detect it automatically.');
}

class Bridge {
  constructor(context, output) {
    this.context = context;
    this.output = output;
    this.nextId = 1;
    this.pending = new Map();
    this.process = undefined;
  }

  start() {
    if (this.process) return;
    const jar = this.context.asAbsolutePath(path.join('target', 'mysql-routine-debugger-vscode-server.jar'));
    if (!fs.existsSync(jar)) {
      throw new Error('The debugger server is missing. Run "mvn package" at the repository root before launching the extension.');
    }
    const configuredJava = vscode.workspace.getConfiguration('mysqlRoutineDebugger').get('javaPath', 'java');
    const javaPath = discoverJava(configuredJava);
    this.output.appendLine(`Using Java ${javaMajor(javaPath)}: ${javaPath}`);
    this.stderr = '';
    this.process = cp.spawn(javaPath, ['-jar', jar], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    readline.createInterface({ input: this.process.stdout }).on('line', line => this.receive(line));
    this.process.stderr.on('data', data => {
      const text = data.toString();
      this.stderr = (this.stderr + text).slice(-8000);
      this.output.append(text);
    });
    this.process.on('error', error => this.failAll(error));
    this.process.on('exit', code => {
      const expected = this.stopping;
      this.process = undefined;
      this.stopping = false;
      if (!expected) {
        let detail = this.stderr.trim();
        if (detail.includes('UnsupportedClassVersionError')) {
          detail = 'Java 17 or newer is required. Set mysqlRoutineDebugger.javaPath to a Java 17+ executable in VS Code Settings.';
        }
        this.failAll(new Error(detail || `Debugger server exited with code ${code}.`));
      }
    });
  }

  request(method, params = {}) {
    this.start();
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.process.stdin.write(`${JSON.stringify({ id, method, params })}\n`, error => {
        if (error) {
          this.pending.delete(id);
          reject(error);
        }
      });
    });
  }

  receive(line) {
    try {
      const message = JSON.parse(line);
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(message.error));
      else pending.resolve(message.result);
    } catch (error) {
      this.output.appendLine(`Invalid debugger response: ${line}`);
    }
  }

  failAll(error) {
    for (const pending of this.pending.values()) pending.reject(error);
    this.pending.clear();
  }

  dispose() {
    if (!this.process) return;
    this.stopping = true;
    this.process.stdin.write(`${JSON.stringify({ id: this.nextId++, method: 'shutdown', params: {} })}\n`);
    const process = this.process;
    setTimeout(() => { if (process.exitCode === null) process.kill(); }, 1000);
  }
}

class ListProvider {
  constructor(items, makeItem) {
    this.items = items;
    this.makeItem = makeItem;
    this.emitter = new vscode.EventEmitter();
    this.onDidChangeTreeData = this.emitter.event;
  }
  getTreeItem(item) { return this.makeItem(item); }
  getChildren() { return this.items(); }
  refresh() { this.emitter.fire(undefined); }
  dispose() { this.emitter.dispose(); }
}

function routineItem(routine) {
  const item = new vscode.TreeItem(routine.name, vscode.TreeItemCollapsibleState.None);
  item.description = routine.type.toLowerCase();
  item.iconPath = new vscode.ThemeIcon(routine.type === 'FUNCTION' ? 'symbol-function' : 'symbol-method');
  item.command = { command: 'mysqlRoutineDebugger.load', title: 'Open Routine', arguments: [routine] };
  return item;
}

function isExecutable(text) {
  const value = text.trim().toUpperCase();
  if (!value || value.startsWith('--') || value.startsWith('#') || value.startsWith('/*') || value.startsWith('*/')) return false;
  if (value.startsWith('CREATE ') || value.startsWith('DEFINER') || value.startsWith('DECLARE ') || value === 'BEGIN') return false;
  return !/^END(\s+(IF|WHILE|LOOP|REPEAT|CASE))?[\s;]*$/.test(value);
}

function activate(context) {
  const output = vscode.window.createOutputChannel('MySQL Routine Debugger');
  const bridge = new Bridge(context, output);
  const state = {
    routines: [], watches: new Map(), log: [], breakpoints: new Set(), lastId: 0,
    connected: false, active: false, paused: false, polling: false, watchAll: false,
    currentLine: -1, statusText: 'Ready', statusKind: 'normal', controlEpoch: 0
  };
  const routines = new ListProvider(() => state.routines, routineItem);
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  status.name = 'MySQL Routine Debugger';
  status.command = 'mysqlRoutineDebugger.open';
  status.text = '$(debug-disconnect) MySQL Routine Debugger';
  status.tooltip = 'Open MySQL Routine Debugger';
  status.show();
  let panel;

  const setContext = (key, value) => vscode.commands.executeCommand('setContext', `mysqlRoutineDebugger.${key}`, value);
  const snapshot = () => ({
    routines: state.routines,
    routine: state.routine,
    ddl: state.ddl || '',
    breakpoints: [...state.breakpoints],
    watches: [...state.watches.entries()].map(([name, value]) => ({ name, ...value })),
    log: state.log.slice(-1000),
    connected: state.connected,
    active: state.active,
    paused: state.paused,
    watchAll: state.watchAll,
    currentLine: state.currentLine,
    statusText: state.statusText,
    statusKind: state.statusKind,
    schema: state.schema
  });
  const render = () => {
    if (panel) panel.webview.postMessage({ type: 'state', state: snapshot() });
  };
  const setStatus = (text, kind = 'normal') => {
    state.statusText = text;
    state.statusKind = kind;
    status.text = `${kind === 'paused' ? '$(debug-pause)' : '$(database)'} ${text}`;
    status.backgroundColor = kind === 'paused' ? new vscode.ThemeColor('statusBarItem.errorBackground') : undefined;
    render();
  };
  const showError = error => {
    output.appendLine(error.stack || String(error));
    vscode.window.showErrorMessage(`MySQL Routine Debugger: ${error.message || error}`);
    if (panel) panel.webview.postMessage({ type: 'error', message: error.message || String(error) });
  };
  const stopPolling = () => {
    if (state.pollTimer) clearInterval(state.pollTimer);
    state.pollTimer = undefined;
    state.polling = false;
  };
  const applyEntries = entries => {
    if (!entries.length) return false;
    for (const entry of entries) {
      state.log.push(entry);
      if (entry.varName !== '__BREAKPOINT__') {
        const watched = state.watches.get(entry.varName);
        if (watched || state.watchAll) {
          const previous = watched && watched.value;
          state.watches.set(entry.varName, {
            value: entry.varValue,
            changed: previous !== undefined && previous !== entry.varValue
          });
        }
      }
      state.lastId = Math.max(state.lastId, entry.id);
    }
    if (state.log.length > 1000) state.log.splice(0, state.log.length - 1000);
    render();
    return true;
  };
  const poll = async () => {
    if (!state.active || state.polling) return;
    state.polling = true;
    const epoch = state.controlEpoch;
    try {
      const result = await bridge.request('poll', { sessionId: state.sessionId, sinceId: state.lastId });
      if (epoch !== state.controlEpoch) return;
      applyEntries(result.entries || []);
      if (result.paused) {
        let line = result.pausedLine || (/^L\d+$/.test(result.pausedAt || '') ? Number(result.pausedAt.slice(1)) : -1);
        // Clearing the log removes the persisted breakpoint marker but must not
        // erase the current source location while the DB session remains paused.
        if (line < 1 && state.paused && state.currentLine > 0) line = state.currentLine;
        const changedPause = !state.paused || state.currentLine !== line;
        state.paused = true;
        state.currentLine = line;
        if (changedPause) {
          await setContext('paused', true);
          setStatus(`Paused at ${state.routine.name}:${line > 0 ? line : result.pausedAt}`, 'paused');
        }
      } else if (!result.paused && state.paused) {
        state.paused = false; state.currentLine = -1; await setContext('paused', false);
        setStatus(`Debugging ${state.routine.name}`);
      }
    } catch (error) { output.appendLine(`Poll failed: ${error.message}`); }
    finally { state.polling = false; }
  };
  const startPolling = () => {
    stopPolling();
    const interval = vscode.workspace.getConfiguration('mysqlRoutineDebugger').get('pollInterval', 600);
    state.pollTimer = setInterval(poll, interval);
    poll();
  };

  async function connect(connection) {
    openPanel();
    const config = vscode.workspace.getConfiguration('mysqlRoutineDebugger');
    if (!connection) {
      const defaults = {
        engine: config.get('engine', 'mysql'), host: config.get('host', 'localhost'), port: config.get('port', 3306),
        user: config.get('user', ''), database: config.get('database', '')
      };
      panel.webview.postMessage({ type: 'showConnection', connection: defaults });
      return;
    }
    const { host, user, database } = connection;
    const engine = connection.engine || 'mysql';
    const portText = String(connection.port || 3306);
    const secretKey = `mysqlRoutineDebugger:${engine}:${host}:${portText}:${database}:${user}`;
    const password = connection.password || await context.secrets.get(secretKey) || '';
    setStatus('Connecting…');
    const result = await bridge.request('connect', { engine, host, port: Number(portText), user, password, database });
    await context.secrets.store(secretKey, password);
    await Promise.all([
      config.update('engine', engine, vscode.ConfigurationTarget.Global),
      config.update('host', host, vscode.ConfigurationTarget.Global),
      config.update('port', Number(portText), vscode.ConfigurationTarget.Global),
      config.update('user', user, vscode.ConfigurationTarget.Global),
      config.update('database', database, vscode.ConfigurationTarget.Global)
    ]);
    state.routines = result.routines || []; state.connected = true; state.schema = result.schema; state.engine = result.engine;
    routines.refresh(); await setContext('connected', true); setStatus(`Connected to ${result.schema}`);
    panel.webview.postMessage({ type: 'connected' });
  }

  async function loadRoutine(routine) {
    openPanel();
    if (!routine) {
      routine = await vscode.window.showQuickPick(state.routines.map(r => ({ label: r.name, description: r.type, routine: r })), { placeHolder: 'Choose a routine' });
      routine = routine && routine.routine;
    }
    if (!routine) return;
    const result = await bridge.request('load', routine);
    state.routine = routine; state.ddl = result.ddl; state.breakpoints = new Set(result.breakpoints || []);
    state.active = result.deployed; state.sessionId = result.sessionId; state.lastId = 0; state.paused = false;
    state.currentLine = -1; state.log = []; state.watches.clear();
    await setContext('loaded', true); await setContext('active', state.active); await setContext('paused', false);
    if (state.active) { startPolling(); setStatus(`Debugging ${routine.name}`); }
    else { stopPolling(); setStatus(`Loaded ${routine.name}`); }
    render();
  }

  async function deploy() {
    if (!state.routine) return;
    setStatus(`Deploying ${state.routine.name}…`);
    const result = await bridge.request('deploy', state.routine);
    state.sessionId = result.sessionId; state.active = true; state.lastId = 0; state.log = []; state.currentLine = -1;
    await setContext('active', true); startPolling();
    setStatus(`Debug active — call ${state.routine.name}(…) in your SQL client`);
  }

  async function stop() {
    if (!state.active) return;
    setStatus(`Stopping ${state.routine.name}…`);
    const result = await bridge.request('stop', state.routine);
    stopPolling(); state.active = false; state.paused = false; state.ddl = result.ddl; state.currentLine = -1;
    await setContext('active', false); await setContext('paused', false);
    setStatus(`Stopped debugging ${state.routine.name}`);
  }

  async function resume(mode) {
    if (!state.paused) return;
    state.controlEpoch++;
    state.paused = false; state.currentLine = -1;
    for (const value of state.watches.values()) value.changed = false;
    await setContext('paused', false);
    setStatus(mode === 'step' ? 'Stepping…' : 'Continuing…');
    await bridge.request(mode, { sessionId: state.sessionId });
    render();
    poll();
  }

  async function toggleBreakpoint(line, text) {
    if (!state.routine || !isExecutable(text || (state.ddl || '').split(/\r?\n/)[line - 1] || '')) return;
    const label = `L${line}`;
    if (state.breakpoints.has(label)) state.breakpoints.delete(label); else state.breakpoints.add(label);
    render();
    await bridge.request('saveBreakpoints', { name: state.routine.name, labels: [...state.breakpoints] });
  }

  function addWatch(name) {
    name = String(name || '').trim();
    if (name && !state.watches.has(name)) {
      const latest = [...state.log].reverse().find(entry => entry.varName !== '__BREAKPOINT__' && entry.varName.toLowerCase() === name.toLowerCase());
      state.watches.set(name, latest ? { value: latest.varValue, changed: false } : {});
    }
    render();
  }

  function webviewHtml(webview) {
    const script = webview.asWebviewUri(vscode.Uri.joinPath(context.extensionUri, 'resources', 'webview.js'));
    const style = webview.asWebviewUri(vscode.Uri.joinPath(context.extensionUri, 'resources', 'webview.css'));
    const nonce = Math.random().toString(36).slice(2);
    return `<!DOCTYPE html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
      <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${webview.cspSource}; script-src 'nonce-${nonce}';">
      <link rel="stylesheet" href="${style}"><title>MySQL Routine Debugger</title></head>
      <body>
        <header class="toolbar">
          <button id="connect" class="secondary">Connect</button><span class="separator"></span>
          <select id="routine" aria-label="Routine"><option value="">Select a routine…</option></select>
          <button id="load">Load</button><button id="debug" class="success">▶ Debug</button><button id="stop" class="danger">■ Stop</button>
          <span class="separator wide"></span><button id="continue" class="primary">▶ Continue <kbd>F5</kbd></button><button id="step" class="purple">↓ Step <kbd>F8</kbd></button>
          <span class="spacer"></span><button id="reset" class="icon" title="Reset all debug changes">⋮</button>
        </header>
        <div id="banner" class="banner hidden"></div>
        <main class="workspace">
          <section class="source-pane"><div id="empty" class="empty">Connect and choose a routine to begin.</div><div id="source" class="source"></div></section>
          <aside class="side-pane">
            <section class="panel watches"><div class="panel-title"><span>Watches</span><label><input id="watch-all" type="checkbox"> All</label></div>
              <form id="watch-form"><input id="watch-name" placeholder="Variable name"><button title="Add watch">＋</button></form>
              <div class="table-wrap"><table><thead><tr><th>Name</th><th>Value</th><th></th></tr></thead><tbody id="watch-list"></tbody></table></div>
            </section>
            <section class="panel logs"><div class="panel-title"><span>Debug Log</span><button id="clear-log" class="link">Clear</button></div>
              <div class="table-wrap"><table><thead><tr><th>Time</th><th>Label</th><th>Variable</th><th>Value</th></tr></thead><tbody id="log-list"></tbody></table></div>
            </section>
          </aside>
        </main>
        <footer id="status" class="status">Ready</footer>
        <dialog id="connection-dialog"><form id="connection-form" method="dialog"><h2>Connect to MySQL or MariaDB</h2>
          <div class="connection-grid"><label class="full">Database engine<select id="db-engine"><option value="mysql">MySQL</option><option value="mariadb">MariaDB</option></select></label>
          <label>Host<input id="db-host" required></label><label>Port<input id="db-port" type="number" required></label>
          <label>User<input id="db-user" required></label><label>Password<input id="db-password" type="password" placeholder="Use saved password"></label>
          <label class="full">Database / schema<input id="db-database" required></label></div>
          <div id="connection-error" class="form-error"></div><div class="dialog-actions"><button value="cancel" class="secondary">Cancel</button><button id="connect-submit" value="default" class="primary">Connect</button></div>
        </form></dialog>
        <div id="context-menu" class="context-menu hidden"><button id="context-add-watch"></button></div>
        <script nonce="${nonce}" src="${script}"></script>
      </body></html>`;
  }

  function openPanel() {
    if (panel) { panel.reveal(vscode.ViewColumn.One); render(); return panel; }
    panel = vscode.window.createWebviewPanel('mysqlRoutineDebugger.panel', 'MySQL Routine Debugger', vscode.ViewColumn.One, {
      enableScripts: true, retainContextWhenHidden: true,
      localResourceRoots: [vscode.Uri.joinPath(context.extensionUri, 'resources')]
    });
    panel.iconPath = vscode.Uri.joinPath(context.extensionUri, 'resources', 'database-debug.svg');
    panel.webview.html = webviewHtml(panel.webview);
    panel.onDidDispose(() => { panel = undefined; }, null, context.subscriptions);
    panel.webview.onDidReceiveMessage(async message => {
      try {
        switch (message.type) {
          case 'ready':
            render();
            if (!state.connected) {
              const config = vscode.workspace.getConfiguration('mysqlRoutineDebugger');
              panel.webview.postMessage({ type: 'showConnection', connection: {
                engine: config.get('engine', 'mysql'), host: config.get('host', 'localhost'), port: config.get('port', 3306),
                user: config.get('user', ''), database: config.get('database', '')
              }});
            }
            break;
          case 'showConnection': await connect(); break;
          case 'connect': await connect(message.connection); break;
          case 'load': await loadRoutine(state.routines.find(r => r.name === message.name)); break;
          case 'deploy': await deploy(); break;
          case 'stop': await stop(); break;
          case 'continue': await resume('continue'); break;
          case 'step': await resume('step'); break;
          case 'toggleBreakpoint': await toggleBreakpoint(message.line, message.text); break;
          case 'addWatch': addWatch(message.name); break;
          case 'removeWatch': state.watches.delete(message.name); render(); break;
          case 'watchAll': state.watchAll = Boolean(message.enabled); render(); break;
          case 'clearLog':
            if (state.sessionId) await bridge.request('clearLog', { sessionId: state.sessionId });
            state.log = []; state.lastId = 0; render(); break;
          case 'reset': await vscode.commands.executeCommand('mysqlRoutineDebugger.reset'); break;
        }
      } catch (error) { showError(error); }
    }, null, context.subscriptions);
    return panel;
  }

  const command = (name, handler) => context.subscriptions.push(vscode.commands.registerCommand(`mysqlRoutineDebugger.${name}`, (...args) => Promise.resolve(handler(...args)).catch(showError)));
  command('open', openPanel);
  command('connect', connect);
  command('disconnect', async () => { stopPolling(); await bridge.request('disconnect'); state.connected = false; state.routines = []; routines.refresh(); await setContext('connected', false); setStatus('Ready'); });
  command('refresh', async () => { state.routines = await bridge.request('routines'); routines.refresh(); render(); });
  command('load', loadRoutine);
  command('deploy', deploy);
  command('stop', stop);
  command('continue', () => resume('continue'));
  command('step', () => resume('step'));
  command('toggleBreakpoint', () => panel && panel.webview.postMessage({ type: 'toggleSelectedBreakpoint' }));
  command('addWatch', async () => {
    const name = await vscode.window.showInputBox({ title: 'Watch variable', prompt: 'Stored routine parameter or local variable name' });
    addWatch(name);
  });
  command('removeWatch', watch => { if (watch && watch.name) { state.watches.delete(watch.name); render(); } });
  command('clearLog', async () => { if (state.sessionId) await bridge.request('clearLog', { sessionId: state.sessionId }); state.log = []; state.lastId = 0; render(); });
  command('reset', async () => {
    const answer = await vscode.window.showWarningMessage('Restore all deployed routines and remove all debugger infrastructure?', { modal: true }, 'Reset All');
    if (answer !== 'Reset All') return;
    stopPolling(); const result = await bridge.request('reset'); state.routines = result.routines || []; state.active = false; state.routine = undefined; state.ddl = ''; state.currentLine = -1;
    routines.refresh(); await setContext('active', false); await setContext('loaded', false); setStatus('All debug changes reverted');
  });

  context.subscriptions.push(
    output, bridge, routines, status,
    vscode.window.registerTreeDataProvider('mysqlRoutineDebugger.routines', routines)
  );
  setContext('connected', false); setContext('loaded', false); setContext('active', false); setContext('paused', false);
}

function deactivate() {}

module.exports = { activate, deactivate, discoverJava, javaMajor };
