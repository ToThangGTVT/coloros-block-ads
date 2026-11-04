//! Incremental, conservative head tokenizer. Holds one unfinished tag, never the whole head.
use bytes::Bytes;

const MAX_TAG: usize = 8 * 1024;
pub(super) struct Scanner {
    pending: Vec<u8>,
    quote: u8,
    head: bool,
    raw: Option<&'static [u8]>,
    comment: bool,
    dashes: u8,
    pub(super) stopped: bool,
    pub(super) inserted: bool,
    style: Bytes,
    leading: Vec<u8>,
}
impl Scanner {
    pub(super) fn new(style: Bytes) -> Self {
        Self {
            pending: Vec::new(),
            quote: 0,
            head: false,
            raw: None,
            comment: false,
            dashes: 0,
            stopped: false,
            inserted: false,
            style,
            leading: Vec::with_capacity(2),
        }
    }
    pub(super) fn write(&mut self, data: Bytes) -> Bytes {
        if self.stopped {
            return data;
        }
        let mut output = Vec::with_capacity(data.len());
        for (at, &byte) in data.iter().enumerate() {
            if self.leading.len() < 2 {
                self.leading.push(byte);
                if self.leading == [0xff, 0xfe] || self.leading == [0xfe, 0xff] {
                    self.stopped = true;
                }
            }
            if byte == 0 {
                self.stopped = true;
            }
            if self.stopped {
                output.append(&mut self.pending);
                output.extend_from_slice(&data[at..]);
                break;
            }
            if self.comment {
                output.push(byte);
                if byte == b'>' && self.dashes == 2 {
                    self.comment = false;
                }
                self.dashes = if byte == b'-' {
                    (self.dashes + 1).min(2)
                } else {
                    0
                };
                continue;
            }
            if self.pending.is_empty() {
                if byte == b'<' {
                    self.pending.push(byte);
                    self.quote = 0;
                } else {
                    output.push(byte);
                }
                continue;
            }
            self.pending.push(byte);
            if self.pending.len() > MAX_TAG {
                // Malformed/unusually long individual tags pass through; ordinary inline
                // scripts/styles/comments of any length do not use this buffer.
                self.stopped = true;
                output.append(&mut self.pending);
                continue;
            }
            if let Some(closing) = self.raw {
                let legacy = closing == b"</script" && b"<!--".starts_with(&self.pending);
                if legacy && self.pending.len() == 4 {
                    self.stopped = true; // legacy double-escaped script grammar: leave untouched
                    output.append(&mut self.pending);
                    continue;
                }
                let prefix = self.pending.len() <= closing.len()
                    && closing[..self.pending.len()].eq_ignore_ascii_case(&self.pending);
                let closing_tag = self.pending.len() > closing.len()
                    && self.pending[..closing.len()].eq_ignore_ascii_case(closing)
                    && (self.pending[closing.len()].is_ascii_whitespace()
                        || self.pending[closing.len()] == b'>');
                if !legacy && !prefix && !closing_tag {
                    // Reconsume a trailing '<' so even adjacent candidates span chunks correctly.
                    let trailing = self.pending.last() == Some(&b'<');
                    if trailing {
                        self.pending.pop();
                    }
                    output.append(&mut self.pending);
                    if trailing {
                        self.pending.push(b'<');
                    }
                    continue;
                }
                if !closing_tag {
                    continue;
                }
            } else if b"<!--".starts_with(&self.pending) {
                if self.pending.len() == 4 {
                    self.comment = true;
                    self.dashes = 0;
                    output.append(&mut self.pending);
                }
                continue;
            }
            if self.quote != 0 {
                if byte == self.quote {
                    self.quote = 0;
                }
            } else if matches!(byte, b'\'' | b'"') {
                self.quote = byte;
            } else if byte == b'>' {
                if self.raw.is_some() {
                    self.raw = None;
                } else if self.tag() {
                    output.extend_from_slice(&self.style);
                    self.inserted = true;
                    self.stopped = true;
                }
                output.append(&mut self.pending);
            }
        }
        Bytes::from(output)
    }
    fn tag(&mut self) -> bool {
        let content = &self.pending[1..self.pending.len() - 1];
        let name = content
            .split(|c| c.is_ascii_whitespace() || *c == b'/')
            .next()
            .unwrap_or_default();
        if content
            .strip_prefix(b"/")
            .is_some_and(|n| n.trim_ascii_end().eq_ignore_ascii_case(b"head"))
        {
            self.stopped = true;
            return self.head;
        }
        if name.eq_ignore_ascii_case(b"head") {
            self.head = true;
        }
        if name.eq_ignore_ascii_case(b"body") || name.eq_ignore_ascii_case(b"template") {
            self.stopped = true;
        }
        if self.head
            && !name.is_empty()
            && ![
                b"head".as_slice(),
                b"html",
                b"base",
                b"basefont",
                b"bgsound",
                b"link",
                b"meta",
                b"title",
                b"script",
                b"style",
                b"noscript",
                b"noframes",
            ]
            .iter()
            .any(|n| name.eq_ignore_ascii_case(n))
        {
            self.stopped = true;
        }
        if name.eq_ignore_ascii_case(b"meta") && !super::safe_meta(content) {
            self.stopped = true;
        }
        for (name_match, closing) in [
            (b"script".as_slice(), b"</script".as_slice()),
            (b"style", b"</style"),
            (b"title", b"</title"),
            (b"textarea", b"</textarea"),
            (b"noscript", b"</noscript"),
            (b"noframes", b"</noframes"),
        ] {
            if name.eq_ignore_ascii_case(name_match) {
                self.raw = Some(closing);
                break;
            }
        }
        false
    }
    pub(super) fn finish(&mut self) -> Bytes {
        self.stopped = true;
        Bytes::from(std::mem::take(&mut self.pending))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tokens_can_be_split_at_any_byte_without_changing_the_document() {
        let input = b"<!doctype html><html><head data-x='>'><!-- </head> --><meta charset=utf-8><title></head></title><script>let fake='</head>'; let t='<</scriptX>';</script><style>/* </head> */</style></head><body>text</body></html>";
        let at = input
            .windows(12)
            .position(|s| s == b"</head><body")
            .unwrap();
        let mut expected = input[..at].to_vec();
        expected.extend_from_slice(b"<style data-test></style>");
        expected.extend_from_slice(&input[at..]);
        for chunk_size in 1..=input.len() {
            let mut scanner = Scanner::new(Bytes::from_static(b"<style data-test></style>"));
            let mut output = Vec::new();
            for chunk in input.chunks(chunk_size) {
                output.extend_from_slice(&scanner.write(Bytes::copy_from_slice(chunk)));
                assert!(scanner.pending.len() <= MAX_TAG);
            }
            output.extend_from_slice(&scanner.finish());
            assert_eq!(output, expected, "chunk size {chunk_size}");
        }
    }
    #[test]
    fn large_raw_text_and_comments_stream_with_constant_token_memory() {
        for (start, end) in [
            (b"<head><style>".as_slice(), b"</style></head>".as_slice()),
            (b"<head><script>", b"</script></head>"),
            (b"<head><!--", b"--></head>"),
        ] {
            let mut scanner = Scanner::new(Bytes::from_static(b"INJECT"));
            assert_eq!(scanner.write(Bytes::copy_from_slice(start)), start);
            let chunk = Bytes::from(vec![b'x'; 4096]);
            for _ in 0..1024 {
                assert_eq!(scanner.write(chunk.clone()), chunk);
                assert!(scanner.pending.is_empty());
            }
            let output = scanner.write(Bytes::copy_from_slice(end));
            assert!(scanner.inserted);
            let mut expected = end[..end.len() - 7].to_vec();
            expected.extend_from_slice(b"INJECT</head>");
            assert_eq!(output, expected);
        }
    }
    #[test]
    fn malformed_long_tags_and_incomplete_tokens_pass_through_without_growth() {
        let input = [
            b"<head><meta name='".as_slice(),
            &vec![b'x'; MAX_TAG * 3],
            b"'></head>",
        ]
        .concat();
        let mut scanner = Scanner::new(Bytes::from_static(b"INJECT"));
        let mut output = Vec::new();
        for chunk in input.chunks(97) {
            output.extend_from_slice(&scanner.write(Bytes::copy_from_slice(chunk)));
            assert!(scanner.pending.len() <= MAX_TAG);
        }
        output.extend_from_slice(&scanner.finish());
        assert_eq!(output, input);
        assert!(!scanner.inserted);
    }
}
