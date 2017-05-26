#!/usr/bin/python3

import sys

from collections import defaultdict

def main(argv):

    inputfile = ""

    if len(argv) > 2 or len(argv) == 0:
        print('Usage: verify.py <inputfile> --separator=')
        sys.exit(2)
    else:
        inputfile = argv[0]

    separator = ","
    if len(argv) == 2 and "--separator=" in argv[1]:
        separator = argv[1][12:]

    parse(inputfile, separator)

def parse(inputfile, separator):

    totalOperatorCounts = defaultdict(int)
    maxOperatorSum = defaultdict(int)
    numberCount = defaultdict(int)
    total_lines = 0
    max_number = 0

    with open(inputfile) as file:
        for line in file:
            timestamp, number, name, operator_count = line.split(separator)

            number = int(number)
            operator_count = int(operator_count)
            name = name.strip() # Remove leading and trailing whitespace

            totalOperatorCounts[name] += 1
            total_lines += 1

            if maxOperatorSum[name] < operator_count:
                maxOperatorSum[name] = operator_count

            numberCount[number] += 1

            if number > max_number:
                max_number = number

    for operator in maxOperatorSum.keys():
        if maxOperatorSum[operator] != totalOperatorCounts[operator]:
            print("OperatorSum for operator '", operator, "' does not match!")
            print("maxOperatorSum: ", maxOperatorSum[operator], "totalOperatorCounts: ", totalOperatorCounts[operator])

    max_number += 1 # Increment max_number since counting starts at 0

    # Assume 1 was received correctly by all sources
    numberOfParallelSources = numberCount[1]

    for key, value in sorted(numberCount.items()):
        if value != numberOfParallelSources:
            print(key, " has not been received by all sources, instead", value, " #Sources: ", numberOfParallelSources)

    if total_lines != max_number * numberOfParallelSources:
        print("Number of lines does not match counted elements!")
        print("-> Total lines: ", total_lines, " - Max Number: ", max_number)

    if total_lines != sum(totalOperatorCounts.values()):
        print("Number of lines does not match counted elements per operator!")
        print("Saw operators: ", totalOperatorCounts.keys())

    print("Saw ", total_lines, " lines")

if __name__ == "__main__":
    main(sys.argv[1:])
