# Korean drug / running-impact wiki note workflow

Use this when adding a medication note to the user's wiki, especially when the user asks for the drug's active ingredient and practical impact on running/endurance exercise.

## Reliable source pattern

- For Korean brand drugs, start with 약학정보원 (`health.kr`) by searching the brand name plus `주성분` or `site:health.kr <제품명>`.
  - The drug detail page often exposes the key facts in the HTML `meta description`, even when the visible page is noisy.
  - Capture: product title, ingredient/amount, indication, and important adverse-reaction snippets such as hypotension or dizziness.
- Cross-check with MFDS `nedrug.mfds.go.kr` when possible, but be careful: brand-name searches can surface combination products first (e.g. telmisartan/amlodipine) rather than the single-ingredient product the user meant.
- For English authoritative safety language, openFDA labels are useful:
  - `https://api.fda.gov/drug/label.json?search=openfda.generic_name:%22<GENERIC_NAME>%22&limit=1`
  - Useful fields: `indications_and_usage`, `warnings_and_cautions`, `adverse_reactions`, `drug_interactions`.

## Writing style for wiki health notes

- Put the note into the most relevant existing page if one exists; for endurance/running implications, an existing running page may be better than creating a separate drug page.
- Make clear that the section is informational, not medical advice.
- Include practical exercise implications rather than only pharmacology:
  - dehydration / heat / salt loss interactions with blood-pressure lowering
  - dizziness, postural hypotension, unusual fatigue, chest pain, fainting symptoms
  - kidney-function and potassium cautions when relevant
  - NSAID caution after long runs if the drug label warns about kidney-risk interactions
  - do not adjust or stop medication without the prescribing clinician
- Keep claims grounded in retrieved sources; avoid performance-enhancement or impairment claims unless directly supported.