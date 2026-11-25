import { Link } from 'react-router-dom'
import CoverImage from './CoverImage'
import { BookOpenIcon, HeartIcon, PlayIcon } from './Icons'

export interface AnimePosterCardProps {
  title: string
  coverUrl: string | null | undefined
  detailHref: string
  playHref: string
  actionKind?: 'play' | 'read'
  topLeft?: string | null
  topLeftVariant?: 'dark' | 'accent' | 'pub'
  rating?: number | null
  bottomLeft?: string | null
  bottomRight?: string | null
  metaLeft?: string | null
  metaTag?: string | null
  progressPct?: number | null
  canFavorite?: boolean
  favorited?: boolean
  busy?: boolean
  onToggleFavorite?: () => void
}

/**
 * 统一作品海报卡片（番剧 / 漫画共享）：
 * - 3:4 高清海报 + 四角角标 + 底部渐变暗角；
 * - 悬浮/键盘聚焦浮现淡青柔焦蒙层 + 48px 圆形主播放/阅读按钮 + 40px 圆形心形收藏按钮；
 * - 紧凑底栏：单行截断标题（悬浮变青）+ 左副信息 + 右淡蓝题材/理由胶囊。
 */
export default function AnimePosterCard({
  title, coverUrl, detailHref, playHref, actionKind = 'play',
  topLeft, topLeftVariant = 'dark', rating, bottomLeft, bottomRight,
  metaLeft, metaTag, progressPct,
  canFavorite = true, favorited = false, busy = false, onToggleFavorite,
}: AnimePosterCardProps) {
  const showBottom = Boolean(bottomLeft || bottomRight)
  const isRead = actionKind === 'read'

  return (
    <article className="anime-poster-card">
      <div className="apc-poster">
        <Link to={detailHref} className="apc-poster-link" aria-label={`查看 ${title}`}>
          <CoverImage src={coverUrl} alt={title} />
          {topLeft && (
            <span className={'apc-slot' + (topLeftVariant !== 'dark' ? ` is-${topLeftVariant}` : '')}>
              {topLeft}
            </span>
          )}
          {typeof rating === 'number' && rating > 0 && (
            <span className="apc-score tabular-nums">★ {rating.toFixed(1)}</span>
          )}
          {showBottom && (
            <span className="apc-bottom">
              <span className="apc-bottom-left">{bottomLeft ?? ''}</span>
              <span className="apc-bottom-right tabular-nums">{bottomRight ?? ''}</span>
            </span>
          )}
          {typeof progressPct === 'number' && (
            <div className="card-progress" aria-hidden="true">
              <div className="card-progress-fill" style={{ width: `${progressPct}%` }} />
            </div>
          )}
        </Link>

        <div className="apc-actions">
          <Link to={playHref} className={'apc-play' + (isRead ? ' is-read' : '')}
                aria-label={isRead ? `阅读 ${title}` : `播放 ${title}`}
                title={isRead ? '开始阅读' : '立即播放'}>
            {isRead ? <BookOpenIcon size={20} /> : <PlayIcon size={22} />}
          </Link>
          {canFavorite && onToggleFavorite && (
            <button type="button"
                    className={'apc-fav' + (favorited ? ' active' : '')}
                    aria-pressed={favorited}
                    aria-busy={busy}
                    disabled={busy}
                    aria-label={favorited ? `取消收藏 ${title}` : `收藏 ${title}`}
                    title={favorited ? '取消收藏' : '收藏'}
                    onClick={e => { e.preventDefault(); e.stopPropagation(); onToggleFavorite() }}>
              <HeartIcon size={19} filled={favorited} />
            </button>
          )}
        </div>
      </div>

      <div className="apc-caption">
        <h3 title={title}>
          <Link to={detailHref}>{title}</Link>
        </h3>
        <div className="apc-meta">
          <span className="apc-meta-left" title={metaLeft ?? ''}>{metaLeft || '番剧'}</span>
          {metaTag && <span className="apc-genre" title={metaTag}>{metaTag}</span>}
        </div>
      </div>
    </article>
  )
}
