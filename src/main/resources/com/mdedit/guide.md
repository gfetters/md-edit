# Markdown Guide

Welcome to **MD Edit**! Markdown is a simple way to format text using plain
characters. Type on the left and see the result on the right.

This guide is itself a Markdown document. Edit it freely to experiment;
your changes are not saved unless you choose **File > Save As**.

**Contents**

- [Headings](#headings)
- [Paragraphs and line breaks](#paragraphs-and-line-breaks)
- [Emphasis](#emphasis)
- [Lists](#lists)
- [Links and images](#links-and-images)
- [Code](#code)
- [Block quotes](#block-quotes)
- [Tables](#tables)
- [Horizontal rules](#horizontal-rules)
- [Escaping and special characters](#escaping-and-special-characters)
- [HTML](#html)
- [Cheat sheet](#cheat-sheet)

---

## Headings

Start a line with one to six `#` characters followed by a space:

```
# Heading 1
## Heading 2
### Heading 3
#### Heading 4
##### Heading 5
###### Heading 6
```

### Heading 3

#### Heading 4

##### Heading 5

###### Heading 6

You can also underline a line with `===` (level 1) or `---` (level 2):

```
Big title
=========

Smaller title
-------------
```

Every heading gets an anchor, so you can link to it: `[Jump to Lists](#lists)`
gives [Jump to Lists](#lists).

## Paragraphs and line breaks

Paragraphs are separated by a blank line.
Lines that follow each other directly
are joined into one paragraph.

To force a line break, end a line with two spaces  
or with a backslash\
like this.

## Emphasis

| You type | You get |
|----------|---------|
| `*italic*` or `_italic_` | *italic* |
| `**bold**` or `__bold__` | **bold** |
| `***bold italic***` | ***bold italic*** |
| `~~strikethrough~~` | ~~strikethrough~~ |
| `` `inline code` `` | `inline code` |

Emphasis can be nested: **bold with *italic* inside**, *italic with **bold** inside*.

Underscores inside words are left alone, so snake_case_names and
file_names_like_this.txt stay readable.

## Lists

### Bullet lists

Start lines with `-`, `*` or `+`. Indent by two spaces to nest:

```
- Fruit
  - Apples
  - Oranges
- Vegetables
  * Carrots
```

- Fruit
  - Apples
  - Oranges
- Vegetables
  * Carrots

### Numbered lists

Use a number followed by `.` or `)`. The first number sets the start:

```
3. Third
4. Fourth
5. Fifth
```

3. Third
4. Fourth
5. Fifth

Lists can be mixed:

1. Prepare
   - read the guide
   - open a file
2. Write
3. Save

### Task lists

```
- [x] Write the draft
- [ ] Proofread
- [ ] Publish
```

- [x] Write the draft
- [ ] Proofread
- [ ] Publish

### Loose lists

Leave blank lines between items to give them more room:

- First item with a little more space.

- Second item.

  An item can contain several paragraphs: indent them to line up with the
  item's text.

## Links and images

| You type | You get |
|----------|---------|
| `[MD Edit](https://example.com)` | [MD Edit](https://example.com) |
| `[with title](https://example.com "Hover text")` | [with title](https://example.com "Hover text") |
| `<https://example.com>` | <https://example.com> |
| `<someone@example.com>` | <someone@example.com> |
| plain `https://example.com` | https://example.com |

**Reference links** keep long URLs out of your text:

```
Read the [CommonMark spec][spec] and the [GFM spec].

[spec]: https://spec.commonmark.org/ "CommonMark"
[GFM spec]: https://github.github.com/gfm/
```

Read the [CommonMark spec][spec] and the [GFM spec].

[spec]: https://spec.commonmark.org/ "CommonMark"
[GFM spec]: https://github.github.com/gfm/

**Images** look like links with a `!` in front:

```
![Alt text](images/picture.png "Optional title")
```

Relative image paths are resolved against the folder of the saved file, so
save your document next to its images. Links to other `.md` files open them
in the editor.

## Code

Inline code uses backticks: `` `print("hi")` `` gives `print("hi")`.
To include a backtick, use two: ``` `` a ` b `` ``` gives `` a ` b ``.

Code blocks are fenced with three backticks (or three tildes). Add a
language name after the opening fence if you like:

````
```java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello, world!");
    }
}
```
````

```java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello, world!");
    }
}
```

~~~
Tilde fences work too.
~~~

You can also indent every line by four spaces:

    $ java -jar md-edit.jar notes.md

## Block quotes

Start lines with `>`. Quotes can be nested and can contain other elements:

```
> A quote.
>
> > A quote inside a quote.
>
> - with a list
> - and **formatting**
```

> A quote.
>
> > A quote inside a quote.
>
> - with a list
> - and **formatting**

## Tables

Separate columns with `|` and put a line of dashes under the header.
Colons in that line set the alignment:

```
| Left | Center | Right |
|:-----|:------:|------:|
| a    |   b    |     c |
| one  |  two   | three |
```

| Left | Center | Right |
|:-----|:------:|------:|
| a    |   b    |     c |
| one  |  two   | three |

The outer pipes are optional. To put a `|` inside a cell, write `\|`
(code spans such as `a|b` are kept together):

| Expression | Meaning |
|------------|---------|
| `a \|\| b` | logical or |
| x \| y | a literal pipe |

## Horizontal rules

Three or more `-`, `*` or `_` on a line by themselves (with a blank line
above, otherwise `---` makes the previous line a heading):

```
---
***
___
```

---

## Escaping and special characters

Put a backslash in front of a character to show it literally:
\*not italic\*, \# not a heading, \[not a link\], 1\. not a list.

HTML entities work: &copy; &trade; &rarr; &hearts; &#8364;. A lone
ampersand such as AT&T is shown as is.

## HTML

A few simple HTML tags can be used inline, such as <b>b</b>, <i>i</i>,
<u>underline</u>, <sub>sub</sub>script, <sup>super</sup>script and
<kbd>Ctrl</kbd>.

Block-level HTML (like `<div>`) is passed through as is:

<div align="center">This line is centered with a &lt;div&gt;.</div>

HTML comments are hidden from the preview: <!-- you cannot see me -->

## Cheat sheet

| Element | Markdown |
|---------|----------|
| Heading | `# H1`, `## H2`, `### H3` |
| Bold | `**text**` |
| Italic | `*text*` |
| Strikethrough | `~~text~~` |
| Code | `` `code` `` |
| Link | `[title](https://url)` |
| Image | `![alt](image.png)` |
| Bullet list | `- item` |
| Numbered list | `1. item` |
| Task | `- [ ] todo`, `- [x] done` |
| Quote | `> text` |
| Code block | ` ``` ` on lines before and after |
| Table | `\| a \| b \|` + `\|---\|---\|` |
| Rule | `---` |
| Line break | two spaces or `\` at end of line |

**Tip:** the **Format** menu inserts most of these for you, and
**File > Export as HTML** saves a nicely styled web page.
