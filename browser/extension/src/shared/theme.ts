export type ThemeChoice = 'system' | 'light' | 'dark';

export async function applyTheme(): Promise<ThemeChoice> {
  let t: ThemeChoice = 'system';
  try {
    const { theme } = await chrome.storage.local.get('theme');
    if (theme === 'light' || theme === 'dark') t = theme;
  } catch {
    /* preference only */
  }
  if (t === 'system') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = t;
  return t;
}

export async function setTheme(t: ThemeChoice) {
  try {
    await chrome.storage.local.set({ theme: t });
  } catch {
    /* preference only */
  }
  await applyTheme();
}
