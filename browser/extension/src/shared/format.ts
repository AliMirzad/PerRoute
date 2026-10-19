export function bytes(n: number | null | undefined): string {
  if (n === null || n === undefined || !Number.isFinite(n)) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = n;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)} ${units[i]}`;
}

export function rate(bytesPerSecond: number): string {
  return `${bytes(bytesPerSecond)}/s`;
}

/** Megabits per second, as speed tests are usually shown. */
export function mbps(bytesPerSecond: number): string {
  const v = (bytesPerSecond * 8) / 1_000_000;
  return `${v >= 100 ? Math.round(v) : v.toFixed(1)} Mbit/s`;
}

export function ago(unixSeconds: number | null | undefined, now = Date.now() / 1000): string {
  if (!unixSeconds) return 'never';
  const d = Math.max(0, now - unixSeconds);
  if (d < 60) return 'just now';
  if (d < 3600) return `${Math.floor(d / 60)} min ago`;
  if (d < 86400) return `${Math.floor(d / 3600)} h ago`;
  return `${Math.floor(d / 86400)} d ago`;
}

export function until(unixSeconds: number, now = Date.now() / 1000): string {
  const d = Math.max(0, unixSeconds - now);
  if (d < 60) return 'less than a minute';
  if (d < 3600) return `${Math.ceil(d / 60)} min`;
  if (d < 86400) return `${Math.floor(d / 3600)} h ${Math.floor((d % 3600) / 60)} min`;
  return `${Math.floor(d / 86400)} days`;
}

export function date(unixSeconds: number): string {
  return new Date(unixSeconds * 1000).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

export function dateTime(unixSeconds: number): string {
  return new Date(unixSeconds * 1000).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

export const DURATIONS = [15, 30, 60, 120, 240];

export function durationLabel(minutes: number): string {
  return minutes < 60 ? `${minutes} min` : `${minutes / 60} h`;
}
