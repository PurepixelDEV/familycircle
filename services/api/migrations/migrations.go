// Package migrations embeds the SQL schema migrations into the binary.
package migrations

import "embed"

// FS contains files named NNNN_description.sql, applied in lexical order.
//
//go:embed *.sql
var FS embed.FS
