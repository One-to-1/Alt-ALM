import type { Choice, GridColumn } from '../api/client.ts'

/**
 * One editable field, and the rule deciding which control it gets.
 *
 * <h2>Why this is shared rather than written twice</h2>
 *
 * Both the editor and the create form render the same fields, and the rule below is subtle enough
 * that two copies would drift within a release. It already had to be corrected once: a single
 * fallback was applied to all three "field with choices" mechanisms, which is wrong, and the
 * component tests caught it.
 *
 * <h2>⚠️ Three unrelated mechanisms, and the type cannot tell them apart</h2>
 *
 * <ol>
 *   <li><strong>LookupList + `listId`</strong> → the project's lookup lists. 56 of the model's 58
 *       LookupList fields are bound this way. The stored value is the literal string.
 *   <li><strong>Reference with `fieldRelationReferences`</strong> → a query against another entity
 *       collection (Target Release → `release`). The stored value is an entity <strong>id</strong>.
 *   <li><strong>Reference with NO references</strong> → the subtype endpoint. `type-id` is this.
 *       Also an <strong>id</strong>.
 * </ol>
 *
 * {@code type-id} and {@code target-rel} are both {@code REFERENCE} and resolve by different
 * routes, which is why everything here branches on {@code choiceSource} and never on the type.
 */

/**
 * Whether this field's value is a record id rather than a literal.
 *
 * The distinction that decides the fallback: an id typed by hand is a wrong pointer, a string typed
 * by hand is just a value ALM can reject.
 */
export function pointsAtARecord(column: GridColumn): boolean {
  return column.choiceSource === 'ENTITY' || column.choiceSource === 'SUBTYPE'
}

/**
 * The values this field permits, or null to fall back.
 *
 * Null in every "cannot tell" case, mirroring the BFF validator exactly: choices not loaded, field
 * offers none, list unknown to this project, or list defined with no items. That last one matters —
 * three of the sandbox's 39 lists are empty, and an empty dropdown makes a field impossible to fill
 * rather than merely unconstrained.
 */
export function choicesFor(
  column: GridColumn,
  choices: Record<string, Choice[]> | null,
): Choice[] | null {
  if (column.choiceSource === 'NONE' || choices === null) return null
  const values = choices[column.name]
  return values && values.length > 0 ? values : null
}

/**
 * The fields worth offering on a form, in the order given.
 *
 * `writable` is `!virtual` and nothing more — see GridDto.Column. `required` and `editable` are
 * deliberately absent from the contract so no form can grey out a field ALM actually demands
 * (probe 9). Memo fields are excluded because they are HTML documents and a text input would
 * flatten them.
 *
 * Multi-value fields are INCLUDED as of probe 33, which established how a multi-value write is
 * spelled. They were excluded while that was unknown — a single-value control over one would have
 * silently dropped the other values on save, and guessing the wire shape was the alternative.
 */
export function editableColumns(columns: GridColumn[]): GridColumn[] {
  return columns.filter(
    (c) => c.writable && c.type !== 'MEMO' && c.name !== 'id' && c.name !== 'ver-stamp',
  )
}
