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
    const jar = this.context.asAbsolutePath(path.join('target', 'proc-debugger-vscode-server.jar'));
    if (!fs.existsSync(jar)) {
      throw new Error('The debugger server is missing. Run "mvn package" at the repository root before launching the extension.');
    }
    const configuredJava = vscode.workspace.getConfiguration('mariaDbDebugger').get('javaPath', 'java');
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
          detail = 'Java 17 or newer is required. Set mariaDbDebugger.javaPath to a Java 17+ executable in VS Code Settings.';
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

class SourceProvider {
  constructor(state) {
    this.state = state;
    this.emitter = new vscode.EventEmitter();
    this.onDidChange = this.emitter.event;
  }
  provideTextDocumentContent() { return this.state.ddl || ''; }
  refresh() { if (this.state.sourceUri) this.emitter.fire(this.state.sourceUri); }
  dispose() { this.emitter.dispose(); }
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
  item.command = { command: 'mariaDbDebugger.load', title: 'Open Routine', arguments: [routine] };
  return item;
}

function watchItem(watch) {
  const item = new vscode.TreeItem(watch.name, vscode.TreeItemCollapsibleState.None);
  item.description = watch.value === undefined ? 'not observed' : String(watch.value);
  item.tooltip = `${watch.name} = ${item.description}`;
  item.contextValue = 'watch';
  item.iconPath = new vscode.ThemeIcon(watch.changed ? 'arrow-swap' : 'eye');
  return item;
}

function logItem(entry) {
  const breakpoint = entry.varName === '__BREAKPOINT__';
  const label = breakpoint ? `Paused at ${entry.varValue}` : `${entry.varName} = ${entry.varValue ?? 'NULL'}`;
  const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
  item.description = `${entry.routineName} · ${entry.label}`;
  item.tooltip = `${entry.ts}\n${entry.routineName} · ${entry.label}\n${label}`;
  item.iconPath = new vscode.ThemeIcon(breakpoint ? 'debug-breakpoint' : 'output');
  return item;
}

function isExecutable(text) {
  const value = text.trim().toUpperCase();
  if (!value || value.startsWith('--') || value.startsWith('#') || value.startsWith('/*') || value.startsWith('*/')) return false;
  if (value.startsWith('CREATE ') || value.startsWith('DEFINER') || value.startsWith('DECLARE ') || value === 'BEGIN') return false;
  return !/^END(\s+(IF|WHILE|LOOP|REPEAT|CASE))?[\s;]*$/.test(value);
}

function activate(context) {
  const output = vscode.window.createOutputChannel('MariaDB Procedure Debugger');
  const bridge = new Bridge(context, output);
  const state = {
    routines: [], watches: new Map(), log: [], breakpoints: new Set(), lastId: 0,
    connected: false, active: false, paused: false, polling: false
  };
  const source = new SourceProvider(state);
  const routines = new ListProvider(() => state.routines, routineItem);
  const watches = new ListProvider(() => [...state.watches.entries()].map(([name, value]) => ({ name, ...value })), watchItem);
  const log = new ListProvider(() => [...state.log].reverse(), logItem);
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  status.name = 'MariaDB Procedure Debugger';
  status.command = 'mariaDbDebugger.connect';
  status.text = '$(debug-disconnect) MariaDB Debugger';
  status.tooltip = 'Connect to MariaDB';
  status.show();
  const currentLineDecoration = vscode.window.createTextEditorDecorationType({
    isWholeLine: true,
    backgroundColor: new vscode.ThemeColor('editor.stackFrameHighlightBackground'),
    overviewRulerColor: new vscode.ThemeColor('editorOverviewRuler.findMatchForeground'),
    overviewRulerLane: vscode.OverviewRulerLane.Full
  });
  const breakpointDecoration = vscode.window.createTextEditorDecorationType({
    isWholeLine: true,
    before: { contentText: '●', color: new vscode.ThemeColor('debugIcon.breakpointForeground'), margin: '0 8px 0 0' }
  });

  const setContext = (key, value) => vscode.commands.executeCommand('setContext', `mariaDbDebugger.${key}`, value);
  const setStatus = (text, paused = false) => {
    status.text = `${paused ? '$(debug-pause)' : '$(database)'} ${text}`;
    status.backgroundColor = paused ? new vscode.ThemeColor('statusBarItem.errorBackground') : undefined;
  };
  const showError = error => {
    output.appendLine(error.stack || String(error));
    vscode.window.showErrorMessage(`MariaDB Debugger: ${error.message || error}`);
  };
  const editor = () => vscode.window.visibleTextEditors.find(e => state.sourceUri && e.document.uri.toString() === state.sourceUri.toString());
  const refreshDecorations = currentLine => {
    const activeEditor = editor();
    if (!activeEditor) return;
    const bpRanges = [...state.breakpoints]
      .map(label => Number(label.slice(1)) - 1)
      .filter(line => line >= 0 && line < activeEditor.document.lineCount)
      .map(line => activeEditor.document.lineAt(line).range);
    activeEditor.setDecorations(breakpointDecoration, bpRanges);
    activeEditor.setDecorations(currentLineDecoration, currentLine > 0 && currentLine <= activeEditor.document.lineCount
      ? [activeEditor.document.lineAt(currentLine - 1).range] : []);
    if (currentLine > 0) activeEditor.revealRange(activeEditor.document.lineAt(currentLine - 1).range, vscode.TextEditorRevealType.InCenterIfOutsideViewport);
  };
  const stopPolling = () => {
    if (state.pollTimer) clearInterval(state.pollTimer);
    state.pollTimer = undefined;
    state.polling = false;
  };
  const applyEntries = entries => {
    for (const entry of entries) {
      state.log.push(entry);
      if (entry.varName !== '__BREAKPOINT__') {
        const watched = state.watches.get(entry.varName);
        if (watched) state.watches.set(entry.varName, { value: entry.varValue, changed: watched.value !== undefined && watched.value !== entry.varValue });
      }
      state.lastId = Math.max(state.lastId, entry.id);
    }
    if (state.log.length > 1000) state.log.splice(0, state.log.length - 1000);
    watches.refresh(); log.refresh();
  };
  const poll = async () => {
    if (!state.active || state.polling) return;
    state.polling = true;
    try {
      const result = await bridge.request('poll', { sessionId: state.sessionId, sinceId: state.lastId });
      applyEntries(result.entries || []);
      if (result.paused && !state.paused) {
        state.paused = true; await setContext('paused', true);
        const line = result.pausedLine || (/^L\d+$/.test(result.pausedAt || '') ? Number(result.pausedAt.slice(1)) : -1);
        refreshDecorations(line);
        setStatus(`Paused at ${state.routine.name}:${line > 0 ? line : result.pausedAt}`, true);
      } else if (!result.paused && state.paused) {
        state.paused = false; await setContext('paused', false); refreshDecorations(-1);
        setStatus(`Debugging ${state.routine.name}`);
      }
    } catch (error) { output.appendLine(`Poll failed: ${error.message}`); }
    finally { state.polling = false; }
  };
  const startPolling = () => {
    stopPolling();
    const interval = vscode.workspace.getConfiguration('mariaDbDebugger').get('pollInterval', 600);
    state.pollTimer = setInterval(poll, interval);
    poll();
  };

  async function connect() {
    const config = vscode.workspace.getConfiguration('mariaDbDebugger');
    const ask = async (title, value, password = false) => vscode.window.showInputBox({ title, value, password, ignoreFocusOut: true });
    const host = await ask('MariaDB host', config.get('host', 'localhost')); if (host === undefined) return;
    const portText = await ask('MariaDB port', String(config.get('port', 3306))); if (portText === undefined) return;
    const user = await ask('MariaDB user', config.get('user', '')); if (user === undefined) return;
    const database = await ask('MariaDB database/schema', config.get('database', '')); if (database === undefined) return;
    const secretKey = `mariaDbDebugger:${host}:${portText}:${database}:${user}`;
    const password = await ask('MariaDB password', await context.secrets.get(secretKey) || '', true); if (password === undefined) return;
    setStatus('Connecting…');
    const result = await bridge.request('connect', { host, port: Number(portText), user, password, database });
    await context.secrets.store(secretKey, password);
    await Promise.all([
      config.update('host', host, vscode.ConfigurationTarget.Global),
      config.update('port', Number(portText), vscode.ConfigurationTarget.Global),
      config.update('user', user, vscode.ConfigurationTarget.Global),
      config.update('database', database, vscode.ConfigurationTarget.Global)
    ]);
    state.routines = result.routines || []; state.connected = true;
    routines.refresh(); await setContext('connected', true); setStatus(`Connected to ${result.schema}`);
  }

  async function loadRoutine(routine) {
    if (!routine) {
      routine = await vscode.window.showQuickPick(state.routines.map(r => ({ label: r.name, description: r.type, routine: r })), { placeHolder: 'Choose a routine' });
      routine = routine && routine.routine;
    }
    if (!routine) return;
    const result = await bridge.request('load', routine);
    state.routine = routine; state.ddl = result.ddl; state.breakpoints = new Set(result.breakpoints || []);
    state.active = result.deployed; state.sessionId = result.sessionId; state.lastId = 0; state.paused = false;
    state.sourceUri = vscode.Uri.parse(`mariadb-debug:/${encodeURIComponent(routine.name)}.sql`);
    source.refresh();
    const document = await vscode.workspace.openTextDocument(state.sourceUri);
    await vscode.window.showTextDocument(document, { preview: false });
    await setContext('loaded', true); await setContext('active', state.active); await setContext('paused', false);
    refreshDecorations(-1);
    if (state.active) { startPolling(); setStatus(`Debugging ${routine.name}`); }
    else { stopPolling(); setStatus(`Loaded ${routine.name}`); }
  }

  async function deploy() {
    if (!state.routine) return;
    setStatus(`Deploying ${state.routine.name}…`);
    const result = await bridge.request('deploy', state.routine);
    state.sessionId = result.sessionId; state.active = true; state.lastId = 0; state.log = [];
    log.refresh(); await setContext('active', true); startPolling();
    setStatus(`Debug active — call ${state.routine.name}(…) in your SQL client`);
  }

  async function stop() {
    if (!state.active) return;
    setStatus(`Stopping ${state.routine.name}…`);
    const result = await bridge.request('stop', state.routine);
    stopPolling(); state.active = false; state.paused = false; state.ddl = result.ddl;
    source.refresh(); await setContext('active', false); await setContext('paused', false);
    refreshDecorations(-1); setStatus(`Stopped debugging ${state.routine.name}`);
  }

  const command = (name, handler) => context.subscriptions.push(vscode.commands.registerCommand(`mariaDbDebugger.${name}`, (...args) => Promise.resolve(handler(...args)).catch(showError)));
  command('connect', connect);
  command('disconnect', async () => { stopPolling(); await bridge.request('disconnect'); state.connected = false; state.routines = []; routines.refresh(); await setContext('connected', false); setStatus('MariaDB Debugger'); });
  command('refresh', async () => { state.routines = await bridge.request('routines'); routines.refresh(); });
  command('load', loadRoutine);
  command('deploy', deploy);
  command('stop', stop);
  command('continue', async () => { if (state.paused) { await bridge.request('continue', { sessionId: state.sessionId }); refreshDecorations(-1); setStatus('Continuing…'); } });
  command('step', async () => { if (state.paused) { await bridge.request('step', { sessionId: state.sessionId }); refreshDecorations(-1); setStatus('Stepping…'); } });
  command('toggleBreakpoint', async () => {
    const activeEditor = vscode.window.activeTextEditor;
    if (!activeEditor || !state.routine || activeEditor.document.uri.scheme !== 'mariadb-debug') return;
    const line = activeEditor.selection.active.line;
    if (!isExecutable(activeEditor.document.lineAt(line).text)) { vscode.window.showInformationMessage('Breakpoints can only be set on executable SQL lines.'); return; }
    const label = `L${line + 1}`;
    if (state.breakpoints.has(label)) state.breakpoints.delete(label); else state.breakpoints.add(label);
    await bridge.request('saveBreakpoints', { name: state.routine.name, labels: [...state.breakpoints] });
    refreshDecorations(-1);
  });
  command('addWatch', async () => {
    const name = await vscode.window.showInputBox({ title: 'Watch variable', prompt: 'Stored routine parameter or local variable name' });
    if (name && !state.watches.has(name)) { state.watches.set(name, {}); watches.refresh(); }
  });
  command('removeWatch', watch => { if (watch && watch.name) { state.watches.delete(watch.name); watches.refresh(); } });
  command('clearLog', async () => { if (state.sessionId) await bridge.request('clearLog', { sessionId: state.sessionId }); state.log = []; state.lastId = 0; log.refresh(); });
  command('reset', async () => {
    const answer = await vscode.window.showWarningMessage('Restore all deployed routines and remove all debugger infrastructure?', { modal: true }, 'Reset All');
    if (answer !== 'Reset All') return;
    stopPolling(); const result = await bridge.request('reset'); state.routines = result.routines || []; state.active = false; state.routine = undefined; state.ddl = '';
    routines.refresh(); source.refresh(); await setContext('active', false); await setContext('loaded', false); setStatus('All debug changes reverted');
  });

  context.subscriptions.push(
    output, bridge, source, routines, watches, log, status, currentLineDecoration, breakpointDecoration,
    vscode.workspace.registerTextDocumentContentProvider('mariadb-debug', source),
    vscode.window.registerTreeDataProvider('mariaDbDebugger.routines', routines),
    vscode.window.registerTreeDataProvider('mariaDbDebugger.watches', watches),
    vscode.window.registerTreeDataProvider('mariaDbDebugger.log', log),
    vscode.window.onDidChangeVisibleTextEditors(() => refreshDecorations(-1))
  );
  setContext('connected', false); setContext('loaded', false); setContext('active', false); setContext('paused', false);
}

function deactivate() {}

module.exports = { activate, deactivate, discoverJava, javaMajor };
