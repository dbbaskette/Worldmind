# Worldmind Project Guidelines

## Accessibility / Contrast

The user has difficulty reading low-contrast text. When designing or modifying UI:

- **Never use light gray text on dark gray backgrounds** for active, readable content
- Low-contrast text (e.g. `text-wm_text-dim`) is **only acceptable for completed/finished items** to visually de-emphasize them
- All active labels, metadata, section headers, timestamps, and body text must use `text-wm_text-muted` or higher (`secondary`, `primary`)
- Input placeholders may use `dim` since they disappear when the user types
- When in doubt, use higher contrast — readability over aesthetics

### Text color hierarchy (on `#0B0F19` background)

| Token       | Hex       | Use for                                      |
|-------------|-----------|----------------------------------------------|
| `primary`   | `#F1F5F9` | Headings, primary content, user input         |
| `secondary` | `#CBD5E1` | Body text, descriptions, values               |
| `muted`     | `#94A3B8` | Labels, metadata, timestamps, section headers |
| `dim`       | `#64748B` | Completed items ONLY, input placeholders      |
