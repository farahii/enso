import { isNode } from '@/util/detect'
import init, { is_ident_or_operator, parse, parse_doc_to_json } from 'rust-ffi/pkg/rust_ffi'

if (isNode) {
  const fs = await import('node:fs/promises')
  const buffer = await fs.readFile('./rust-ffi/pkg/rust_ffi_bg.wasm')
  await init(buffer)
} else {
  await init()
}

// eslint-disable-next-line camelcase
export { is_ident_or_operator, parse, parse_doc_to_json }
