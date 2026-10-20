// Package client calls the context service. The service belongs to one project, so no route names
// it; the project is the one the service runs in.
package client

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Root is where every route of the service sits.
const Root = "/v1/context"

// Client is a bearer-token client for one service address.
type Client struct {
	base  string
	token string
	http  *http.Client
}

// New returns a client for the service at base.
func New(base, token string, timeout time.Duration) *Client {
	return &Client{base: strings.TrimRight(base, "/"), token: token, http: &http.Client{Timeout: timeout}}
}

// Error is a refusal from the service, with the words it gave.
type Error struct {
	Status  int
	Message string
}

func (e *Error) Error() string { return e.Message }

// Do sends one request and returns the decoded JSON body, which is nil for an empty body.
func (c *Client) Do(method, path string, body any) (any, error) {
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(raw)
	}
	request, err := http.NewRequest(method, c.base+Root+path, reader)
	if err != nil {
		return nil, err
	}
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if c.token != "" {
		request.Header.Set("Authorization", "Bearer "+c.token)
	}
	response, err := c.http.Do(request)
	if err != nil {
		return nil, fmt.Errorf("cannot reach the service at %s: %w", c.base, err)
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, err
	}
	if response.StatusCode >= 400 {
		return nil, &Error{Status: response.StatusCode, Message: words(response.StatusCode, raw)}
	}
	if len(bytes.TrimSpace(raw)) == 0 {
		return nil, nil
	}
	var decoded any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		return nil, fmt.Errorf("the service answered something that is not JSON: %w", err)
	}
	return decoded, nil
}

// words turns a refusal into a sentence. A JSON body with an error field is used as it stands.
func words(status int, raw []byte) string {
	var shaped struct {
		Error   string `json:"error"`
		Message string `json:"message"`
	}
	if json.Unmarshal(raw, &shaped) == nil {
		if shaped.Error != "" {
			return shaped.Error
		}
		if shaped.Message != "" {
			return shaped.Message
		}
	}
	text := strings.TrimSpace(string(raw))
	switch status {
	case http.StatusUnauthorized, http.StatusBadRequest:
		if text != "" {
			return text
		}
		return "you are not signed in, or the token was refused"
	case http.StatusForbidden:
		return "you do not hold the permission this needs"
	case http.StatusNotFound:
		return "not found"
	}
	if text != "" {
		return text
	}
	return fmt.Sprintf("the service answered %d", status)
}

// Stream sends one request and calls onEvent with the data of each server-sent event until the
// service ends the stream, the caller's function returns false, or the connection fails. It has no
// overall timeout, because a stream stays open for as long as the caller watches.
func (c *Client) Stream(method, path string, body any, onEvent func(data string) bool) error {
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(raw)
	}
	request, err := http.NewRequest(method, c.base+Root+path, reader)
	if err != nil {
		return err
	}
	request.Header.Set("Accept", "text/event-stream")
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if c.token != "" {
		request.Header.Set("Authorization", "Bearer "+c.token)
	}
	response, err := (&http.Client{}).Do(request)
	if err != nil {
		return fmt.Errorf("cannot reach the service at %s: %w", c.base, err)
	}
	defer response.Body.Close()
	if response.StatusCode >= 400 {
		raw, _ := io.ReadAll(response.Body)
		return &Error{Status: response.StatusCode, Message: words(response.StatusCode, raw)}
	}
	scanner := bufio.NewScanner(response.Body)
	scanner.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "data:") && !onEvent(strings.TrimSpace(strings.TrimPrefix(line, "data:"))) {
			return nil
		}
	}
	return scanner.Err()
}

// Text sends one request and returns the body as it is, for an answer that is not JSON.
func (c *Client) Text(method, path string) (string, error) {
	request, err := http.NewRequest(method, c.base+Root+path, nil)
	if err != nil {
		return "", err
	}
	if c.token != "" {
		request.Header.Set("Authorization", "Bearer "+c.token)
	}
	response, err := c.http.Do(request)
	if err != nil {
		return "", fmt.Errorf("cannot reach the service at %s: %w", c.base, err)
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(response.Body)
	if err != nil {
		return "", err
	}
	if response.StatusCode >= 400 {
		return "", &Error{Status: response.StatusCode, Message: words(response.StatusCode, raw)}
	}
	return string(raw), nil
}
