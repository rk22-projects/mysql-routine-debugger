'use strict';

const vscode = acquireVsCodeApi();
let state = {};
let selectedLine = -1;
let contextWord = '';
let renderedCurrentLine = -1;

const $ = id => document.getElementById(id);
const post = (type, extra = {}) => vscode.postMessage({ type, ...extra });
const keywords = new Set(('ACCESSIBLE ADD ALL ALTER ANALYZE AND AS ASC ASENSITIVE BEFORE BETWEEN BIGINT BINARY BLOB BOTH BY CALL CASCADE CASE CHANGE CHAR CHARACTER CHECK COLLATE COLUMN CONDITION CONNECTION CONSTRAINT CONTINUE CONVERT CREATE CROSS CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER CURSOR DATABASE DATABASES DAY_HOUR DAY_MICROSECOND DAY_MINUTE DAY_SECOND DEC DECIMAL DECLARE DEFAULT DELAYED DELETE DESC DESCRIBE DETERMINISTIC DISTINCT DISTINCTROW DIV DOUBLE DROP DUAL EACH ELSE ELSEIF ENCLOSED ESCAPED EXISTS EXIT EXPLAIN FALSE FETCH FLOAT FOR FORCE FOREIGN FROM FULLTEXT GRANT GROUP HAVING HIGH_PRIORITY HOUR_MICROSECOND HOUR_MINUTE HOUR_SECOND IF IGNORE IN INDEX INFILE INNER INOUT INSENSITIVE INSERT INT INTEGER INTERVAL INTO IS ITERATE JOIN KEY KEYS KILL LEADING LEAVE LEFT LIKE LIMIT LINEAR LINES LOAD LOCALTIME LOCALTIMESTAMP LOCK LONG LONGBLOB LONGTEXT LOOP LOW_PRIORITY MASTER_SSL_VERIFY_SERVER_CERT MATCH MEDIUMBLOB MEDIUMINT MEDIUMTEXT MIDDLEINT MINUTE_MICROSECOND MINUTE_SECOND MOD MODIFIES NATURAL NOT NO_WRITE_TO_BINLOG NULL NUMERIC ON OPTIMIZE OPTION OPTIONALLY OR ORDER OUT OUTER OUTFILE PRECISION PRIMARY PROCEDURE PURGE RANGE READ READS READ_WRITE REAL REFERENCES REGEXP RELEASE RENAME REPEAT REPLACE REQUIRE RESTRICT RETURN REVOKE RIGHT RLIKE SCHEMA SCHEMAS SECOND_MICROSECOND SELECT SENSITIVE SEPARATOR SET SHOW SMALLINT SPATIAL SPECIFIC SQL SQLEXCEPTION SQLSTATE SQLWARNING SQL_BIG_RESULT SQL_CALC_FOUND_ROWS SQL_SMALL_RESULT SSL STARTING STRAIGHT_JOIN TABLE TERMINATED THEN TINYBLOB TINYINT TINYTEXT TO TRAILING TRIGGER TRUE UNDO UNION UNIQUE UNLOCK UNSIGNED UPDATE USAGE USE USING UTC_DATE UTC_TIME UTC_TIMESTAMP VALUES VARBINARY VARCHAR VARCHARACTER VARYING WHEN WHERE WHILE WITH WRITE XOR YEAR_MONTH ZEROFILL BEGIN END FUNCTION RETURNS').split(' '));

function textCell(value, title) {
  const td = document.createElement('td');
  td.textContent = value == null ? '' : String(value);
  td.title = title == null ? td.textContent : String(title);
  return td;
}

function valueMap() {
  const values = new Map();
  for (const watch of state.watches || []) if (watch.value !== undefined) values.set(watch.name.toLowerCase(), watch.value);
  for (const entry of state.log || []) if (entry.varName !== '__BREAKPOINT__') values.set(entry.varName.toLowerCase(), entry.varValue);
  return values;
}

function appendSql(code, line, values) {
  const parts = line.match(/--.*$|'(?:''|[^'])*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_]\w*\b|[^A-Za-z_\d'-]+|./g) || [];
  for (const part of parts) {
    const span = document.createElement('span');
    span.textContent = part;
    const upper = part.toUpperCase();
    if (part.startsWith('--')) span.className = 'sql-comment';
    else if (part.startsWith("'")) span.className = 'sql-string';
    else if (/^\d/.test(part)) span.className = 'sql-number';
    else if (keywords.has(upper)) span.className = 'sql-keyword';
    else if (/^[A-Za-z_]\w*$/.test(part)) {
      span.className = 'identifier';
      span.dataset.word = part;
      const value = values.get(part.toLowerCase());
      if (value !== undefined) { span.classList.add('has-value'); span.title = `${part} = ${value == null ? 'NULL' : value}`; }
      else span.title = `Right-click to add ${part} to Watches`;
    }
    code.appendChild(span);
  }
}

function renderSource() {
  const source = $('source');
  const scrollPane = source.parentElement;
  const scrollTop = scrollPane.scrollTop;
  const scrollLeft = scrollPane.scrollLeft;
  const currentLineChanged = state.currentLine > 0 && state.currentLine !== renderedCurrentLine;
  source.replaceChildren();
  const lines = (state.ddl || '').split(/\r?\n/);
  $('empty').classList.toggle('hidden', Boolean(state.ddl));
  source.classList.toggle('hidden', !state.ddl);
  const values = valueMap();
  const fragment = document.createDocumentFragment();
  lines.forEach((text, index) => {
    const number = index + 1;
    const row = document.createElement('div');
    row.className = 'source-line'; row.dataset.line = String(number);
    if (number === state.currentLine) row.classList.add('current');
    if (number === selectedLine) row.classList.add('selected');
    const gutter = document.createElement('button');
    gutter.className = `gutter ${(state.breakpoints || []).includes(`L${number}`) ? 'breakpoint' : ''}`;
    gutter.title = 'Toggle breakpoint (F9)';
    gutter.addEventListener('click', event => { event.stopPropagation(); post('toggleBreakpoint', { line: number, text }); });
    const lineNumber = document.createElement('span'); lineNumber.className = 'line-number'; lineNumber.textContent = String(number);
    const code = document.createElement('span'); code.className = 'code'; appendSql(code, text, values);
    row.append(gutter, lineNumber, code);
    row.addEventListener('click', () => { selectedLine = number; renderSource(); });
    fragment.appendChild(row);
  });
  source.appendChild(fragment);
  renderedCurrentLine = state.currentLine;
  requestAnimationFrame(() => {
    if (currentLineChanged) source.querySelector('.current')?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    else { scrollPane.scrollTop = scrollTop; scrollPane.scrollLeft = scrollLeft; }
  });
}

function renderWatches() {
  const body = $('watch-list'); body.replaceChildren();
  for (const watch of state.watches || []) {
    const row = document.createElement('tr'); if (watch.changed) row.className = 'changed';
    row.append(textCell(watch.name), textCell(watch.value === undefined ? '—' : watch.value, watch.value));
    const remove = document.createElement('td'); remove.className = 'remove';
    const button = document.createElement('button'); button.textContent = '×'; button.title = `Remove ${watch.name}`;
    button.addEventListener('click', () => post('removeWatch', { name: watch.name })); remove.appendChild(button); row.appendChild(remove); body.appendChild(row);
  }
  $('watch-all').checked = Boolean(state.watchAll);
}

function renderLog() {
  const body = $('log-list'); body.replaceChildren();
  for (const entry of [...(state.log || [])].reverse()) {
    const row = document.createElement('tr');
    if (entry.varName === '__BREAKPOINT__') row.className = 'log-break';
    const time = String(entry.ts || '').split(' ').pop();
    row.append(textCell(time), textCell(entry.label), textCell(entry.varName === '__BREAKPOINT__' ? '⏸ breakpoint' : entry.varName), textCell(entry.varValue, entry.varValue));
    body.appendChild(row);
  }
}

function renderToolbar() {
  const select = $('routine');
  const current = state.routine?.name || select.value;
  select.replaceChildren(new Option('Select a routine…', ''));
  for (const routine of state.routines || []) select.add(new Option(`${routine.type === 'PROCEDURE' ? 'P' : 'F'}  ${routine.name}`, routine.name));
  select.value = current;
  select.disabled = !state.connected;
  $('load').disabled = !state.connected || !select.value;
  $('debug').disabled = !state.routine || state.active;
  $('stop').disabled = !state.active;
  $('continue').disabled = !state.paused;
  $('step').disabled = !state.paused;
  $('connect').textContent = state.connected ? `● ${state.schema || 'Connected'}` : 'Connect';
  const banner = $('banner');
  banner.classList.toggle('hidden', !state.active);
  banner.textContent = state.active ? `▶ Debug active — call ${state.routine?.name || ''}(…) normally in your SQL client` : '';
}

function renderStatus() {
  const status = $('status'); status.textContent = state.statusText || 'Ready';
  status.className = `status ${state.statusKind || 'normal'}`;
}

function render() { renderToolbar(); renderSource(); renderWatches(); renderLog(); renderStatus(); }

window.addEventListener('message', event => {
  const message = event.data;
  if (message.type === 'state') { state = message.state; render(); }
  if (message.type === 'showConnection') {
    const c = message.connection || {};
    $('db-engine').value = c.engine || 'mysql';
    $('db-host').value = c.host || 'localhost'; $('db-port').value = c.port || 3306;
    $('db-user').value = c.user || ''; $('db-database').value = c.database || ''; $('db-password').value = '';
    $('connection-error').textContent = ''; $('connection-dialog').showModal();
  }
  if (message.type === 'connected') $('connection-dialog').close();
  if (message.type === 'error' && $('connection-dialog').open) $('connection-error').textContent = message.message;
  if (message.type === 'toggleSelectedBreakpoint' && selectedLine > 0) {
    const text = (state.ddl || '').split(/\r?\n/)[selectedLine - 1] || '';
    post('toggleBreakpoint', { line: selectedLine, text });
  }
});

$('connect').addEventListener('click', () => post('showConnection'));
$('routine').addEventListener('change', event => { $('load').disabled = !event.target.value; });
$('load').addEventListener('click', () => post('load', { name: $('routine').value }));
$('debug').addEventListener('click', () => post('deploy'));
$('stop').addEventListener('click', () => post('stop'));
$('continue').addEventListener('click', () => post('continue'));
$('step').addEventListener('click', () => post('step'));
$('clear-log').addEventListener('click', () => post('clearLog'));
$('watch-all').addEventListener('change', event => post('watchAll', { enabled: event.target.checked }));
$('watch-form').addEventListener('submit', event => { event.preventDefault(); const input = $('watch-name'); post('addWatch', { name: input.value }); input.value = ''; });
$('reset').addEventListener('click', () => post('reset'));
$('connection-form').addEventListener('submit', event => {
  if (event.submitter?.value === 'cancel') return;
  event.preventDefault();
  $('connection-error').textContent = '';
  post('connect', { connection: { engine: $('db-engine').value, host: $('db-host').value.trim(), port: Number($('db-port').value), user: $('db-user').value.trim(), password: $('db-password').value, database: $('db-database').value.trim() } });
});
document.addEventListener('contextmenu', event => {
  const identifier = event.target.closest('.identifier');
  if (!identifier) return;
  event.preventDefault(); contextWord = identifier.dataset.word;
  const menu = $('context-menu'); $('context-add-watch').textContent = `Add “${contextWord}” to Watches`;
  menu.style.left = `${Math.min(event.clientX, innerWidth - 210)}px`; menu.style.top = `${Math.min(event.clientY, innerHeight - 45)}px`; menu.classList.remove('hidden');
});
document.addEventListener('click', event => { if (!event.target.closest('.context-menu')) $('context-menu').classList.add('hidden'); });
$('context-add-watch').addEventListener('click', () => { post('addWatch', { name: contextWord }); $('context-menu').classList.add('hidden'); });
window.addEventListener('keydown', event => {
  if (event.key === 'F5' && state.paused) { event.preventDefault(); post('continue'); }
  if (event.key === 'F8' && state.paused) { event.preventDefault(); post('step'); }
  if (event.key === 'F9' && selectedLine > 0) { event.preventDefault(); const text = (state.ddl || '').split(/\r?\n/)[selectedLine - 1] || ''; post('toggleBreakpoint', { line: selectedLine, text }); }
});

post('ready');
