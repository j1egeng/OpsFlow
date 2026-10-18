import { Languages } from 'lucide-react'
import { useI18n } from '../i18n/I18nContext'

export function LanguageSwitcher() {
  const { locale, setLocale } = useI18n()
  return <label className="language-switcher"><Languages size={16} aria-hidden="true" />
    <select aria-label="界面语言 / Interface language" value={locale} onChange={event => setLocale(event.target.value === 'en' ? 'en' : 'zh-CN')}>
      <option value="zh-CN">简体中文</option><option value="en">English</option>
    </select>
  </label>
}
