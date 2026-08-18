import type { History } from '../api/client.ts'
import './HistoryPanel.css'

interface Props {
  history: History | null
  status: 'loading' | 'ready' | 'error'
  error: string | null
}

/**
 * A record's change history — ALM's Audit Log.
 *
 * <h2>There is no Baselines half, and that is not an oversight</h2>
 *
 * ALM's own History tab is `Baselines | Audit Log`. Baselines live behind the library/baseline API,
 * which probe 12 established is **OTA-only**: every documented REST path for `libraries`,
 * `baselines` and the `vc-*` variants returns 404, and a 1,111-operation inventory of the API has
 * zero hits for them. There is nothing to call, so rather than ship a sub-tab that is permanently
 * empty for an invisible reason, the tab shows the half that exists and the footer says what the
 * other half needs.
 *
 * <h2>The empty state is the important one</h2>
 *
 * "No recorded changes" is NOT "nothing happened to this record". Probe 24 read 678 audit entries
 * across 119 records of a live project and every single one was an `UPDATE` — no creates, no
 * deletes, no memo edits, only 12 distinct fields ever appearing. A record can be created, have its
 * description rewritten twice and gain a coverage link while producing an audit trail of exactly
 * nothing. Saying "no history" without that caveat would be the app asserting something false.
 */
export function HistoryPanel({ history, status, error }: Props) {
  if (status === 'loading') {
    return (
      <div className="history-skeleton" role="status" aria-label="Loading history">
        {Array.from({ length: 4 }, (_, i) => (
          <div key={i} className="history-skeleton-row" />
        ))}
      </div>
    )
  }

  if (status === 'error' || !history) {
    return (
      <p className="history-empty history-error" role="alert">
        {error ?? 'The server did not return this record’s history.'}
      </p>
    )
  }

  return (
    <>
      {history.entries.length === 0 ? (
        <div className="history-empty">
          <p className="history-empty-title">ALM recorded no field changes for this record</p>
          <p className="history-empty-hint">
            That is not the same as nothing having happened. ALM’s audit trail does not record
            creation, deletion, or edits to rich-text fields, so a record can be created and heavily
            edited and still show an empty log.
          </p>
        </div>
      ) : (
        <ol className="history-list">
          {history.entries.map((entry) => (
            <li className="history-entry" key={entry.id}>
              <div className="history-when">
                {/* Rendered exactly as sent. The payload carries no timezone offset, so formatting
                    it as a local time would silently assert the server's zone is this browser's. */}
                <time className="history-time">{entry.time}</time>
                <span className="history-user" title={`Changed by ${entry.user}`}>
                  {entry.user}
                </span>
                {entry.action !== 'UPDATE' && (
                  <span className="history-action">{entry.action.toLowerCase()}</span>
                )}
              </div>

              {entry.changes.length === 0 ? (
                // 85 of 678 entries were like this: an event with nothing recorded about it. Shown
                // rather than dropped, because it is still evidence the record was touched.
                <p className="history-nochange">ALM recorded this change without naming a field.</p>
              ) : (
                <ul className="history-changes">
                  {entry.changes.map((change) => (
                    <li className="history-change" key={`${entry.id}-${change.field}`}>
                      <span className="history-field" title={change.field}>
                        {change.label || change.field}
                      </span>
                      <span className="history-values">
                        <span className="history-old">{change.oldValue || '—'}</span>
                        <span className="history-arrow" aria-label="changed to">
                          →
                        </span>
                        <span className="history-new">{change.newValue || '—'}</span>
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ol>
      )}

      {history.partial && (
        <p className="history-note">
          ALM’s audit trail is partial: creations, deletions and rich-text edits are not recorded.
          {' '}Baselines — the other half of ALM’s History tab — have no documented REST API at all
          and would need the OTA sidecar.
        </p>
      )}
    </>
  )
}
