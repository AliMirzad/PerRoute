// Extension <-> native helper protocol.
// Mirrors helper/src/browser/protocol.rs and helper/src/browser/adapter.rs. See PROTOCOL.md.

export const PROTOCOL_VERSION = 4;
export const HOST_NAME = 'com.perroute.browser';

export type ErrorCode =
  | 'INVALID_REQUEST'
  | 'INCOMPATIBLE_VERSION'
  | 'NOT_FOUND'
  | 'INVALID_CONFIG'
  | 'XRAY_MISSING'
  | 'XRAY_FAILED'
  | 'XRAY_CONFIG_REJECTED'
  | 'PORT_UNAVAILABLE'
  | 'SERVER_UNREACHABLE'
  | 'SUBSCRIPTION_FAILED'
  | 'STORE_ERROR'
  | 'SECURE_STORAGE_UNAVAILABLE'
  | 'BUSY'
  | 'INTERNAL';

export interface ApiError {
  code: ErrorCode;
  message: string;
}

export interface JetbrainsStatus {
  enabled: boolean;
  mode: 'tunnel' | 'direct' | 'off';
  socksPort: number | null;
  httpPort: number | null;
  issue: string | null;
  /** The local endpoint requires the credentials from getIdeCredentials. */
  authRequired: boolean;
}

export type ConnState = 'disconnected' | 'connecting' | 'connected' | 'disconnecting' | 'error';

export interface NativeStatus {
  state: ConnState;
  phase?: 'starting' | 'verifying' | 'restarting';
  serverId?: string;
  /** Browser proxy while connected. `username`/`password` are per-connection credentials that only the
   *  service worker uses (to answer the proxy's 407 challenge); they are stripped before any UI sees the status. */
  proxy?: { scheme: 'http'; host: string; port: number; username?: string; password?: string };
  error?: ApiError;
  since?: number;
  jetbrains: JetbrainsStatus;
  xrayAvailable: boolean;
}

export interface HelloResult {
  nativeVersion: string;
  protocolVersion: number;
  xrayVersion: string | null;
  xrayAvailable: boolean;
  platform: string;
  keyStorage: string;
}

export type Protocol = 'vless' | 'vmess' | 'trojan' | 'shadowsocks';

export interface ServerSummary {
  id: string;
  name: string;
  protocol: Protocol;
  address: string;
  port: number;
  transport: string;
  transportLabel: string;
  security: string;
  securityLabel: string;
  flow: string | null;
  subscriptionId: string | null;
}

export interface SubscriptionUsage {
  upload: number | null;
  download: number | null;
  total: number | null;
  /** Unix seconds. */
  expire: number | null;
}

export interface SubscriptionChange {
  at: number;
  added: number;
  updated: number;
  removed: number;
  rejected: number;
  error: string | null;
}

export interface SubscriptionSummary {
  id: string;
  name: string;
  host: string;
  lastUpdated: number | null;
  lastError: string | null;
  serverCount: number;
  usage?: SubscriptionUsage | null;
  updateIntervalHours?: number | null;
  history?: SubscriptionChange[];
}

export type QualityLabel = 'excellent' | 'good' | 'fair' | 'poor' | 'unavailable' | 'unknown';

export interface ScoreSummary {
  score: number | null;
  label: QualityLabel;
  latencyMs: number | null;
  stale: boolean;
  measuredAt: number;
}

export interface ServerList {
  servers: ServerSummary[];
  subscriptions: SubscriptionSummary[];
  selectedServerId: string | null;
  favorites?: string[];
  tags?: Record<string, string>;
  scores?: Record<string, ScoreSummary>;
  canUndoRestore?: boolean;
}

export interface QualityScore {
  score: number | null;
  label: QualityLabel;
  confidence: number;
  measuredAt: number;
  stale: boolean;
  tunnelVerified: boolean;
  sampleCount: number;
  headline: string;
  explanation: string;
  /** Measurements and their 0-100 part scores (higher is better). */
  components: {
    latencyMs: number | null;
    latencyScore?: number | null;
    jitterMs: number | null;
    jitterScore?: number | null;
    /** 0..1 */
    successRate: number | null;
    successScore?: number | null;
    stabilityScore: number | null;
    setupMs: number | null;
    setupScore?: number | null;
    recoveryPenalty: number;
    recentFailures: number;
  };
}

export interface ImportResult {
  added: number;
  updated: number;
  serverIds: string[];
  errors: { entry: number; message: string }[];
  rejected: number;
  unsupported: number;
  warnings: string[];
}

export interface SubscriptionResult {
  subscriptionId: string;
  added: number;
  updated: number;
  removed: number;
  rejected: number;
  unsupported: number;
}

export type RoutingMode = 'all' | 'selected';
export type RuleMatch = 'domain' | 'ip' | 'port';
export type RuleAction = 'proxy' | 'direct' | 'block';

export interface SiteRule {
  id: string;
  name: string;
  match: RuleMatch;
  values: string[];
  action: RuleAction;
  serverId: string | null;
  enabled: boolean;
  expiresAt: number | null;
  scenario: string | null;
  createdAt: number;
  /** Enabled and not expired. */
  active: boolean;
}

export interface RuleInput {
  name?: string;
  match: RuleMatch;
  text: string;
  action: RuleAction;
  serverId?: string | null;
  minutes?: number | null;
  enabled?: boolean;
}

export interface RulesState {
  rules: SiteRule[];
  mode: RoutingMode;
  localNamesDirect: boolean;
  activeScenario: string | null;
  revision: number;
}

export interface Evaluation {
  host: string;
  outcome: 'proxy' | 'direct' | 'block';
  reason: string;
  server: string | null;
  rule: SiteRule | null;
  /** The one-click rule for exactly this site, if any. */
  siteRule: SiteRule | null;
  connected: boolean;
}

export interface Scenario {
  id: string;
  title: string;
  summary: string;
  highlights: string[];
  killSwitch: boolean;
  autoFailover: boolean;
  fastestOnConnect: boolean;
  rules: { name: string; match: RuleMatch; text: string; action: RuleAction }[];
}

export interface ProfileList {
  profiles: ProfileSummary[];
  activeProfileId: string | null;
}

export interface ProfileSummary {
  id: string;
  name: string;
  serverId: string | null;
  routingMode: RoutingMode;
  rules: number;
  createdAt: number;
}

export interface Settings {
  jetbrainsEnabled: boolean;
  jetbrainsSocksPort: number;
  jetbrainsHttpPort: number;
  passthroughWhenDisconnected: boolean;
  debugLogging: boolean;
  /** Allow subscription URLs on private networks (company-internal servers). Default off. */
  allowPrivateSubscriptionHosts: boolean;
  /** Require a username/password on the local endpoint. Default on. */
  ideAuth: boolean;
  routingMode: RoutingMode;
  localNamesDirect: boolean;
  autoFailover: boolean;
  fastestOnConnect: boolean;
  trafficStats: boolean;
  killSwitch: boolean;
  webrtcProtection: boolean;
  autoConnect: boolean;
  subscriptionUpdateHours: number;
}

export interface TestResult {
  serverId: string;
  ok: boolean;
  ms?: number;
  error?: string;
}

export interface Totals {
  proxyUp: number;
  proxyDown: number;
  directUp: number;
  directDown: number;
}

export interface Traffic {
  enabled: boolean;
  counting: boolean;
  /** Bytes per second. */
  rate: Totals;
  session: Totals;
  today: Totals;
  month: Totals;
  days: (Totals & { day: string })[];
}

export interface ExitInfo {
  ip: string;
  country: string;
  colo: string;
  latencyMs: number;
}

export interface SiteTiming {
  url: string;
  ok: boolean;
  status: number | null;
  ms: number | null;
  error: string | null;
}

export interface DoctorCheck {
  id: string;
  status: 'ok' | 'warn' | 'fail';
  title: string;
  detail: string;
  repair: 'connect' | 'reconnect' | 'switchFastest' | 'addServers' | 'openRules' | null;
}

export interface BackupPreview {
  encrypted: boolean;
  createdAt: number;
  appVersion: string;
  servers: number;
  subscriptions: number;
  rules: number;
  profiles: number;
  hasSettings: boolean;
}

export interface RestoreReport {
  serversAdded: number;
  serversUpdated: number;
  serversRejected: number;
  subscriptions: number;
  rules: number;
  profiles: number;
  settings: boolean;
  warnings: string[];
}

/** Commands the UI may send, with their argument types. */
export interface Commands {
  getStatus: Record<string, never>;
  listServers: Record<string, never>;
  importText: { text: string; source: 'paste' | 'qr' | 'file' };
  addSubscription: { name: string; url: string };
  updateSubscription: { id: string };
  deleteSubscription: { id: string; deleteServers: boolean };
  renameServer: { id: string; name: string };
  deleteServer: { id: string };
  selectServer: { id: string };
  connect: { serverId: string };
  disconnect: Record<string, never>;
  getSettings: Record<string, never>;
  setSettings: Partial<Settings>;
  getDiagnostics: Record<string, never>;
  getIdeCredentials: Record<string, never>;
  regenerateIdeCredentials: Record<string, never>;
  resetAll: { confirm: true };
  setFavorite: { id: string; favorite: boolean };
  setTag: { id: string; tag: string };
  getQuality: { id: string };
  testServers: { ids: string[]; kind: 'tcp' | 'real' };
  speedTest: { serverId?: string };
  connectFastest: Record<string, never>;
  exitInfo: Record<string, never>;
  compare: { candidateId: string; urls: string[] };
  doctor: { host?: string };
  getTraffic: Record<string, never>;
  clearTraffic: Record<string, never>;
  getRules: Record<string, never>;
  addRule: RuleInput;
  updateRule: { id: string; rule: RuleInput };
  deleteRule: { id: string };
  moveRule: { id: string; offset: number };
  setRuleEnabled: { id: string; enabled: boolean };
  setSite: { host: string; action: RuleAction | null; serverId?: string | null; minutes?: number | null };
  evaluate: { host: string };
  replaceRules: { rules: RuleInput[]; mode?: RoutingMode; append: boolean };
  listScenarios: Record<string, never>;
  applyScenario: { id: string };
  clearScenario: Record<string, never>;
  listProfiles: Record<string, never>;
  saveProfile: { name: string };
  applyProfile: { id: string };
  deleteProfile: { id: string };
  exportBackup: { password?: string; servers: boolean; rules: boolean; settings: boolean };
  readExport: { token: number; index: number };
  previewBackup: { text: string; password?: string };
  restoreBackup: { text: string; password?: string; mode: 'merge' | 'replace' };
  undoRestore: Record<string, never>;
}

export type CommandName = keyof Commands;

/** Allow-list enforced by the service worker before forwarding UI requests. */
export const UI_COMMANDS: readonly CommandName[] = [
  'getStatus',
  'listServers',
  'importText',
  'addSubscription',
  'updateSubscription',
  'deleteSubscription',
  'renameServer',
  'deleteServer',
  'selectServer',
  'connect',
  'disconnect',
  'getSettings',
  'setSettings',
  'getDiagnostics',
  'getIdeCredentials',
  'regenerateIdeCredentials',
  'resetAll',
  'setFavorite',
  'setTag',
  'getQuality',
  'testServers',
  'speedTest',
  'connectFastest',
  'exitInfo',
  'compare',
  'doctor',
  'getTraffic',
  'clearTraffic',
  'getRules',
  'addRule',
  'updateRule',
  'deleteRule',
  'moveRule',
  'setRuleEnabled',
  'setSite',
  'evaluate',
  'replaceRules',
  'listScenarios',
  'applyScenario',
  'clearScenario',
  'listProfiles',
  'saveProfile',
  'applyProfile',
  'deleteProfile',
  'exportBackup',
  'readExport',
  'previewBackup',
  'restoreBackup',
  'undoRestore',
] as const;

/** Commands whose answer comes when background work finishes (server tests can take minutes). */
export const LONG_COMMANDS: Partial<Record<CommandName, number>> = {
  testServers: 15 * 60_000,
  connectFastest: 3 * 60_000,
  speedTest: 2 * 60_000,
  compare: 3 * 60_000,
  doctor: 2 * 60_000,
  restoreBackup: 5 * 60_000,
  exportBackup: 2 * 60_000,
};

export type NativeResponse<T = unknown> = { id: number; ok: true; result: T } | { id: number; ok: false; error: ApiError };

export interface Notice {
  kind: 'failover' | 'routeDown' | 'ruleExpired' | 'rulesRejected' | string;
  message: string;
}

export type NativeEvent =
  | { event: 'status'; status: NativeStatus }
  | { event: 'protocolError'; error: ApiError }
  | { event: 'progress'; progress: { id: number; value: TestResult } }
  | { event: 'notice'; notice: Notice }
  | { event: 'changed'; changed: Record<string, never> };

export interface IdeCredentials {
  username: string;
  password: string;
  required: boolean;
  reconnectRequired: boolean;
}
