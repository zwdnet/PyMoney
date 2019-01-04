# -*- coding:utf-8 -*-

import math

class test(object):
    def __init__(self, c):
        self.a = 2
        self.b = c
        
    def hello(self):
        print(self.a)
        print(self.b)
        print(c)
        
        
def testdic():
    letter = {}
    i = 0
    for s in "aabbccdd":
        if letter.__contains__(s) == False:
            letter[s] = i
            i = i+1
    print(letter)
    for key in letter:
        print(key, letter[key])
        
def strjoin():
    s1 = "abc "
    s2 = "123 "
    s3 = "哈哈哈"
    s = s1 + s2 + s3
    print(s)
    
def writefile():
    f = open("test.txt", "w")
    f.write("hello")
    f.close()
    
    
def testTuple():
    t = [(1,)]
    print(t)
    print(t[0])
    print(t[0][0])
    
    
def testNum():
    a = [6.82, 129.32, 6.88, -5, -4.76]
    print(a)
    t = 0
    b = a[:]
    for i in a:
        a[t] = int(100*a[t])
        b[t] = round(a[t]/100, 2)
        print("%.2f" % b[t])
        t += 1
    print(a)
    
    
import decimal
def testDecimal(num):
    decimal.getcontext().prec = 2
    num = 100* decimal.Decimal.from_float(num)
    return num
    
def testDecimal2(num):
    decimal.getcontext().prec = 2
    num = num/decimal.Decimal(100.0)
    return num
    
    
if __name__ == "__main__":
     #hello = test("nihao")
     #hello.hello()
     #testdic()
     #strjoin()
     #writefile()
     #testTuple()
     #testNum()
     for i in range(200):
        x = 0.01*i - 1.0
        x1 = testDecimal(x)
        x2 = testDecimal2(x1)
        print(x, x1, x2)
