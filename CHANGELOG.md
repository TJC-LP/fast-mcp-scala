# Changelog

All notable changes to FastMCP-Scala will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.3] - Bug Fix Release (2026-02-16)

### Fixed
- Exit cleanly on stdin EOF instead of hanging forever (#25)

### Changed
- Updated Scala from 3.7.4 to 3.8.1
- Updated MCP SDK from 0.17.0 to 0.17.2
- Updated WartRemover from 3.4.1 to 3.5.5

## [0.2.2] - Annotation Enhancements (2026-01-12)

### Added
- Made `@Param` `examples` field functional with `List[String]` (#23)
- Made `@Param` annotation `example`, `required`, and `schema` fields functional (#22)

## [0.1.1] - `RefResolver` Patch (2025-05-08)

### Fixed
- Fixed `RefResolver` to handle functions with more than 3 arguments
- Added explicit support for functions with 4-22 arguments
- Added clear error message when attempting to use more than 22 arguments (Scala's built-in limit)

## [0.1.0] - Initial Release (2025-04-25)

### Added
- Initial public release of FastMCP
- Support for Scala-native MCP function tools
- JSON Schema generation for function parameters
- Runtime function resolution