import { createContext, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { LOCALE_KEY, readLocale, translate } from './messages'
import type { Locale } from './messages'

interface I18nState {
  locale: Locale
  setLocale: (locale: Locale) => void
  t: (key: string, values?: Record<string, string | number>) => string
}
const I18nContext = createContext<I18nState | null>(null)

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocale] = useState<Locale>(readLocale)
  useEffect(() => {
    document.documentElement.lang = locale
    document.title = locale === 'en' ? 'OpsFlow · Team workspace' : 'OpsFlow · 团队工作台'
    try { localStorage.setItem(LOCALE_KEY, locale) } catch { /* Language still works when storage is blocked. */ }
  }, [locale])
  return <I18nContext.Provider value={{ locale, setLocale, t: (key, values) => translate(locale, key, values) }}>{children}</I18nContext.Provider>
}

export function useI18n() {
  const state = useContext(I18nContext)
  if (!state) throw new Error('I18nProvider is missing')
  return state
}
