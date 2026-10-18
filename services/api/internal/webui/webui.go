// Package webui serves the compiled web app embedded into the binary.
//
// The Docker build copies apps/web/dist into ./dist before compiling. In a plain `go build`
// only the placeholder index.html is present.
package webui

import (
	"embed"
	"io/fs"
	"net/http"
	"path"
	"strings"
)

//go:embed all:dist
var dist embed.FS

// Handler serves static files; unknown paths without a file extension fall back to index.html
// (client-side routing). Hashed assets are cached forever, everything else is revalidated.
func Handler() http.Handler {
	root, err := fs.Sub(dist, "dist")
	if err != nil {
		panic(err)
	}
	files := http.FileServerFS(root)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		p := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		if p == "" {
			p = "index.html"
		}
		if _, err := fs.Stat(root, p); err != nil {
			if path.Ext(p) != "" {
				http.NotFound(w, r)
				return
			}
			p = "index.html"
		}
		switch {
		case strings.HasPrefix(p, "assets/"):
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		default:
			w.Header().Set("Cache-Control", "no-cache")
		}
		if p == "index.html" {
			// Serve via ServeFileFS to avoid FileServer's redirect of /index.html to /.
			http.ServeFileFS(w, r, root, "index.html")
			return
		}
		r2 := r.Clone(r.Context())
		r2.URL.Path = "/" + p
		files.ServeHTTP(w, r2)
	})
}
