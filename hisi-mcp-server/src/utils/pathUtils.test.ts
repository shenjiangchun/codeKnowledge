import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizePathArgs } from './pathUtils.js';

test('normalizes backslashes in projectPath', () => {
  const result = normalizePathArgs({ projectPath: 'C:\\foo\\bar' });
  assert.deepEqual(result, { projectPath: 'C:/foo/bar' });
});

test('normalizes every element of projectPaths array', () => {
  const result = normalizePathArgs({ projectPaths: ['C:\\a', 'D:\\b'] });
  assert.deepEqual(result, { projectPaths: ['C:/a', 'D:/b'] });
});

test('leaves non-path keys untouched', () => {
  const input = { className: 'Foo\\Bar', limit: 10 };
  const result = normalizePathArgs(input);
  assert.deepEqual(result, { className: 'Foo\\Bar', limit: 10 });
});

test('does not mutate the input object', () => {
  const input = {
    projectPath: 'C:\\foo\\bar',
    projectPaths: ['C:\\a', 'D:\\b'],
    other: 'x',
  };
  const snapshot = JSON.parse(JSON.stringify(input));
  normalizePathArgs(input);
  assert.deepEqual(input, snapshot);
});

test('handles undefined/null path values without throwing', () => {
  const result = normalizePathArgs({ projectPath: undefined, filePath: null });
  assert.deepEqual(result, { projectPath: undefined, filePath: null });
});

test('matches custom *Path / *Paths suffix keys', () => {
  const result = normalizePathArgs({
    sourcePath: 'a\\b',
    targetPaths: ['c\\d'],
  });
  assert.deepEqual(result, {
    sourcePath: 'a/b',
    targetPaths: ['c/d'],
  });
});
