package api_test

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"

	"familycircle/internal/api"
)

// TestRoutesMatchOpenAPI keeps the implementation and packages/api-contract/openapi.yaml in sync:
// every implemented route must be documented and every documented operation implemented.
func TestRoutesMatchOpenAPI(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "packages", "api-contract", "openapi.yaml"))
	if err != nil {
		t.Fatalf("read spec: %v", err)
	}
	var spec struct {
		Paths map[string]map[string]any `yaml:"paths"`
	}
	if err := yaml.Unmarshal(raw, &spec); err != nil {
		t.Fatalf("parse spec: %v", err)
	}
	documented := map[string]bool{}
	for path, ops := range spec.Paths {
		for method := range ops {
			switch method {
			case "get", "post", "put", "patch", "delete":
				documented[strings.ToUpper(method)+" "+path] = true
			}
		}
	}
	implemented := map[string]bool{}
	for _, r := range (&api.Server{}).Routes() {
		implemented[r.Method+" "+r.Path] = true
	}
	var missingDoc, missingImpl []string
	for k := range implemented {
		if !documented[k] {
			missingDoc = append(missingDoc, k)
		}
	}
	for k := range documented {
		if !implemented[k] {
			missingImpl = append(missingImpl, k)
		}
	}
	sort.Strings(missingDoc)
	sort.Strings(missingImpl)
	if len(missingDoc) > 0 {
		t.Errorf("implemented but not in openapi.yaml: %v", missingDoc)
	}
	if len(missingImpl) > 0 {
		t.Errorf("in openapi.yaml but not implemented: %v", missingImpl)
	}
}
